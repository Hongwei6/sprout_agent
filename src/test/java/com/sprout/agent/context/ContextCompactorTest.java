package com.sprout.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

class ContextCompactorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);

    @Test
    void microCompactReplacesOldToolContentBeyondKeepWindowButProtectsReadSkill() throws Exception {
        ContextCompactor compactor = new ContextCompactor(new ContextPolicy(1_000_000, 2, 200, Set.of()));
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage("system"), new UserMessage("生成PPT")));
        // 5 轮工具调用：第 0 轮是受保护的 read_skill，其余是 bash
        for (int i = 0; i < 5; i++) {
            String tool = i == 0 ? "read_skill" : "bash";
            messages.add(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call_" + i, "function", tool,
                            i == 0 ? "{\"name\":\"pptx\"}" : "{\"command\":\"" + "y".repeat(300) + "\"}")))
                    .build());
            messages.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse("call_" + i, tool, "r".repeat(300) + i)))
                    .build());
        }

        compactor.compact(messages, "生成PPT", client);

        // 最近 2 轮保持原样
        ToolResponseMessage fourth = (ToolResponseMessage) messages.get(9);
        ToolResponseMessage fifth = (ToolResponseMessage) messages.get(11);
        assertTrue(fourth.getResponses().get(0).responseData().startsWith("rrr"));
        assertTrue(fifth.getResponses().get(0).responseData().startsWith("rrr"));
        AssistantMessage fourthCall = (AssistantMessage) messages.get(8);
        assertTrue(fourthCall.getToolCalls().get(0).arguments().contains("yyy"));

        // 受保护的 read_skill 内容完整保留
        ToolResponseMessage skill = (ToolResponseMessage) messages.get(3);
        assertEquals("read_skill", skill.getResponses().get(0).name());
        assertTrue(skill.getResponses().get(0).responseData().startsWith("rrr"));

        // 更早的 bash 轮被替换为 JSON 占位符（ToolResponse 与 ToolCall 参数一致）
        ToolResponseMessage compacted = (ToolResponseMessage) messages.get(5);
        JsonNode placeholder = MAPPER.readTree(compacted.getResponses().get(0).responseData());
        assertTrue(placeholder.path("compacted").asBoolean());
        assertEquals("bash", placeholder.path("tool").asText());
        assertEquals(301, placeholder.path("originalLength").asInt());
        AssistantMessage compactedCall = (AssistantMessage) messages.get(4);
        JsonNode argsPlaceholder = MAPPER.readTree(compactedCall.getToolCalls().get(0).arguments());
        assertEquals("args compressed", argsPlaceholder.path("message").asText());
    }

    @Test
    void autoCompactReplacesAllMessagesWithStructuredSummary() {
        when(client.prompt().messages(anyList()).call().content()).thenReturn("结构化摘要内容");
        ContextCompactor compactor = new ContextCompactor(new ContextPolicy(10, 4, 200, Set.of()));
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage("system"), new UserMessage("任务")));
        for (int i = 0; i < 3; i++) {
            messages.add(AssistantMessage.builder().content("round " + i)
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call_" + i, "function", "bash", "{\"c\":1}")))
                    .build());
            messages.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse("call_" + i, "bash", "ok"))).build());
        }

        compactor.compact(messages, "任务", client);

        assertEquals(2, messages.size());
        assertEquals(SystemMessage.class, messages.get(0).getClass());
        assertEquals(UserMessage.class, messages.get(1).getClass());
        assertTrue(messages.get(1).getText().contains("[对话已压缩]"));
        assertTrue(messages.get(1).getText().contains("结构化摘要内容"));
    }

    @Test
    void autoCompactFallsBackToRecentMessagesWhenSummaryFails() {
        when(client.prompt().messages(anyList()).call().content()).thenThrow(new RuntimeException("timeout"));
        ContextCompactor compactor = new ContextCompactor(new ContextPolicy(10, 4, 200, Set.of()));
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage("system"), new UserMessage("任务")));
        for (int i = 0; i < 8; i++) {
            messages.add(AssistantMessage.builder().content("round " + i)
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call_" + i, "function", "bash", "{\"c\":1}")))
                    .build());
            messages.add(ToolResponseMessage.builder().responses(List.of(
                    new ToolResponseMessage.ToolResponse("call_" + i, "bash", "输出" + i))).build());
        }

        compactor.compact(messages, "任务", client);

        String summary = messages.get(1).getText();
        assertTrue(summary.contains("[对话已压缩]"));
        assertTrue(summary.contains("摘要生成失败"));
        assertTrue(summary.contains("输出7"));
        assertFalse(summary.contains("输出2"));
    }

    @Test
    void tokenEstimatorSplitsCjkAndNonCjk() {
        List<Message> messages = List.of(
                new UserMessage("12345678"),            // 8 ASCII → 2
                new UserMessage("你好测试再见啊"),        // 6 CJK → 4
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("c", "function", "bash", "abcd")))
                        .build());                      // 4 ASCII → 1
        assertEquals(7, TokenEstimator.estimateTokens(messages));
    }
}
