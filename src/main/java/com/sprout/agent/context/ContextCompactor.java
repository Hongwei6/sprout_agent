package com.sprout.agent.context;

import com.sprout.agent.prompts.ReactAgentPrompts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 上下文压缩器，两层结构：
 * Layer 1 micro_compact：每轮执行，把超出保留窗口且超过长度阈值的旧工具内容
 * （ToolResponse 内容与 ToolCall 参数）替换为 JSON 占位符，无 LLM 调用，开销极低。
 * Layer 2 auto_compact：micro_compact 后 token 估算仍超阈值时触发，
 * 用 LLM 将全部旧消息（除 SystemMessage）压缩为一条结构化摘要并替换原消息。
 */
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);
    private static final int FALLBACK_KEEP_MESSAGES = 10;

    private final ContextPolicy policy;

    public ContextCompactor(ContextPolicy policy) {
        this.policy = policy;
    }

    public void compact(List<Message> messages, String currentQuestion, ChatClient client) {
        if (messages == null || messages.size() <= 2) return;
        microCompact(messages);
        int estimatedTokens = TokenEstimator.estimateTokens(messages);
        if (estimatedTokens > policy.tokenThreshold()) {
            log.info("上下文 auto_compact 触发：估算 token={} > 阈值={}，消息数={}",
                    estimatedTokens, policy.tokenThreshold(), messages.size());
            autoCompact(messages, currentQuestion, client);
        }
    }

    private void microCompact(List<Message> messages) {
        Map<String, String> toolNameMap = buildToolNameMap(messages);
        List<Integer> toolResponseIndices = new ArrayList<>();
        List<Integer> assistantWithToolCallIndices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage) toolResponseIndices.add(i);
            else if (message instanceof AssistantMessage assistant
                    && assistant.getToolCalls() != null && !assistant.getToolCalls().isEmpty()) {
                assistantWithToolCallIndices.add(i);
            }
        }

        int keep = policy.keepRecentTools();
        int responsesToCompact = Math.max(0, toolResponseIndices.size() - Math.min(keep, toolResponseIndices.size()));
        for (int idx = 0; idx < responsesToCompact; idx++) {
            compactToolResponse(messages, toolResponseIndices.get(idx), toolNameMap);
        }
        if (policy.maxToolLength() > 0) {
            int callsToCompact = Math.max(0, assistantWithToolCallIndices.size() - Math.min(keep, assistantWithToolCallIndices.size()));
            for (int idx = 0; idx < callsToCompact; idx++) {
                compactToolCallArgs(messages, assistantWithToolCallIndices.get(idx));
            }
        }
    }

    private void compactToolResponse(List<Message> messages, int messageIndex, Map<String, String> toolNameMap) {
        ToolResponseMessage original = (ToolResponseMessage) messages.get(messageIndex);
        List<ToolResponseMessage.ToolResponse> replaced = new ArrayList<>(original.getResponses().size());
        boolean changed = false;
        for (ToolResponseMessage.ToolResponse response : original.getResponses()) {
            String content = response.responseData();
            String toolName = response.name() != null ? response.name()
                    : toolNameMap.getOrDefault(response.id(), "unknown");
            if (policy.isProtected(toolName) || content == null || content.length() <= policy.maxToolLength()) {
                replaced.add(response);
                continue;
            }
            replaced.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(),
                    placeholder(toolName, content.length(), "content compressed")));
            changed = true;
        }
        if (changed) {
            messages.set(messageIndex, ToolResponseMessage.builder().responses(replaced).build());
        }
    }

    private void compactToolCallArgs(List<Message> messages, int messageIndex) {
        AssistantMessage original = (AssistantMessage) messages.get(messageIndex);
        List<AssistantMessage.ToolCall> replaced = new ArrayList<>(original.getToolCalls().size());
        boolean changed = false;
        for (AssistantMessage.ToolCall call : original.getToolCalls()) {
            String args = call.arguments();
            if (policy.isProtected(call.name()) || args == null || args.length() <= policy.maxToolLength()) {
                replaced.add(call);
                continue;
            }
            replaced.add(new AssistantMessage.ToolCall(call.id(), call.type(), call.name(),
                    placeholder(call.name(), args.length(), "args compressed")));
            changed = true;
        }
        if (changed) {
            messages.set(messageIndex, AssistantMessage.builder()
                    .content(original.getText()).toolCalls(replaced).build());
        }
    }

    /**
     * 占位符必须是合法 JSON 且与 ToolCall 侧字段一致；自然语言占位符会被部分模型解析失败（400）。
     */
    private static String placeholder(String toolName, int originalLength, String message) {
        return "{\"compacted\":true,\"tool\":\"" + toolName + "\",\"originalLength\":" + originalLength
                + ",\"message\":\"" + message + "\"}";
    }

    private static Map<String, String> buildToolNameMap(List<Message> messages) {
        Map<String, String> map = new HashMap<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    if (call.id() != null) map.put(call.id(), call.name());
                }
            }
        }
        return map;
    }

    private void autoCompact(List<Message> messages, String currentQuestion, ChatClient client) {
        SystemMessage systemMessage = messages.get(0) instanceof SystemMessage system ? system : null;
        int systemCount = systemMessage != null ? 1 : 0;
        List<Message> oldMessages = new ArrayList<>(messages.subList(systemCount, messages.size()));
        String summary = generateSummary(oldMessages, currentQuestion, client);
        messages.clear();
        if (systemMessage != null) messages.add(systemMessage);
        messages.add(new UserMessage("[对话已压缩] 以下是之前对话的摘要：\n" + summary));
        log.info("上下文已压缩：{} 条旧消息合并为结构化摘要", oldMessages.size());
    }

    private String generateSummary(List<Message> oldMessages, String currentQuestion, ChatClient client) {
        String conversationText = buildConversationText(oldMessages);
        try {
            String summary = client.prompt()
                    .messages(List.of(new SystemMessage(ReactAgentPrompts.getCompactSummarySystemPrompt()),
                            new UserMessage(ReactAgentPrompts.getCompactSummaryUserPrompt(
                                    conversationText, currentQuestion))))
                    .call().content();
            return summary == null || summary.isBlank() ? truncationFallback(oldMessages) : summary.strip();
        } catch (Exception ex) {
            log.warn("LLM 摘要生成失败，回退为保留最近 {} 条消息：{}", FALLBACK_KEEP_MESSAGES, ex.getMessage());
            return truncationFallback(oldMessages);
        }
    }

    /**
     * 消息原样拼接，不做截断：截断的脚本/文件内容会让摘要 LLM 误判任务未完成。
     */
    private static String buildConversationText(List<Message> messages) {
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            text.append("[").append(message.getMessageType()).append("] ")
                    .append(extractMessageText(message)).append("\n\n");
        }
        return text.toString();
    }

    private static String extractMessageText(Message message) {
        if (message instanceof AssistantMessage assistant) {
            StringBuilder text = new StringBuilder(assistant.getText() == null ? "" : assistant.getText());
            if (assistant.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    text.append("\n[tool_call] ").append(call.name()).append(" ").append(call.arguments());
                }
            }
            return text.toString();
        }
        if (message instanceof ToolResponseMessage toolResponses) {
            StringBuilder text = new StringBuilder();
            for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                text.append("[tool_response] ").append(response.responseData()).append("\n");
            }
            return text.toString();
        }
        return message.getText() == null ? "" : message.getText();
    }

    private static String truncationFallback(List<Message> messages) {
        int start = Math.max(0, messages.size() - FALLBACK_KEEP_MESSAGES);
        StringBuilder text = new StringBuilder("...[摘要生成失败，以下为最近 ")
                .append(messages.size() - start).append(" 条对话内容]\n\n");
        for (int i = start; i < messages.size(); i++) {
            text.append(extractMessageText(messages.get(i))).append("\n\n");
        }
        return text.toString();
    }
}
