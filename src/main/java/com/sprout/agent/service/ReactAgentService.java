package com.sprout.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class ReactAgentService {

    private static final String LOCAL_API_KEY_PLACEHOLDER = "local-placeholder";
    private static final Pattern SOURCE_URL = Pattern.compile("https?://[^\\s\"\\\\<>]+");

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final ToolCallback[] tools;
    private final String apiKey;
    private final String model;
    private final int maxRounds;
    private final int maxMessages;

    public ReactAgentService(
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            ConversationService conversationService,
            ObjectMapper objectMapper,
            SyncMcpToolCallbackProvider toolProvider,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:}") String model,
            @Value("${sprout.agent.max-rounds:5}") int maxRounds,
            @Value("${sprout.agent.max-messages:20}") int maxMessages) {
        this.chatClientBuilder = chatClientBuilder;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.tools = toolProvider.getToolCallbacks();
        this.apiKey = apiKey;
        this.model = model;
        this.maxRounds = maxRounds;
        if (maxMessages < 2 || maxMessages % 2 != 0) {
            throw new IllegalArgumentException("sprout.agent.max-messages must be a positive even number");
        }
        this.maxMessages = maxMessages;
    }

    public boolean isAvailable() {
        return !apiKey.isBlank() && !LOCAL_API_KEY_PLACEHOLDER.equals(apiKey)
                && !model.isBlank() && chatClientBuilder.getIfAvailable() != null;
    }

    public Flux<ChatEvent> stream(String requestedConversationId, String question) {
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        return Flux.defer(() -> {
            String conversationId = conversationService.createConversation(requestedConversationId, question);
            ChatMemory memory = conversationService.createPersistentChatMemory(conversationId, maxMessages);
            List<Message> messages = new ArrayList<>(memory.get(conversationId));
            messages.add(new UserMessage(question));
            return Flux.just(event("conversation", conversationId))
                    .concatWith(scheduleRound(builder.build(), messages, conversationId, question, new TurnState(), 1))
                    .onErrorResume(ex -> Flux.just(event("error", "对话生成失败，请稍后重试。")));
        });
    }

    private Flux<ChatEvent> scheduleRound(
            ChatClient client, List<Message> messages, String conversationId, String question, TurnState turn, int round) {
        RoundState state = new RoundState();
        return client.prompt().messages(messages)
                .options(OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build())
                .toolCallbacks(tools)
                .stream().chatResponse()
                .concatMap(chunk -> processChunk(chunk, state, turn))
                .concatWith(Flux.defer(() -> finishRound(client, messages, conversationId, question, turn, round, state))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private Flux<ChatEvent> processChunk(ChatResponse chunk, RoundState state, TurnState turn) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return Flux.empty();
        AssistantMessage output = chunk.getResult().getOutput();
        Object reasoning = output.getMetadata().get("reasoningContent");
        if (reasoning instanceof String text && !text.isEmpty()) turn.thinking.append(text);
        if (output.hasToolCalls() || (output.getText() != null && !output.getText().isEmpty())
                || (reasoning instanceof String text && !text.isEmpty())) {
            turn.markFirstResponse();
        }
        if (output.hasToolCalls()) {
            state.toolMode = true;
            for (AssistantMessage.ToolCall call : output.getToolCalls()) mergeToolCall(state, call);
            return Flux.empty();
        }
        String text = output.getText();
        if (text == null || text.isEmpty() || state.toolMode) return Flux.empty();
        state.text.append(text);
        return Flux.just(event("delta", text));
    }

    static void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {
        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);
            if (incoming.id() != null && !incoming.id().isBlank() && incoming.id().equals(existing.id())) {
                state.toolCalls.set(i, new AssistantMessage.ToolCall(existing.id(), "function",
                        existing.name(), Objects.toString(existing.arguments(), "")
                                + Objects.toString(incoming.arguments(), "")));
                return;
            }
        }
        state.toolCalls.add(incoming);
    }

    private Flux<ChatEvent> finishRound(
            ChatClient client, List<Message> messages, String conversationId, String question,
            TurnState turn, int round, RoundState state) {
        if (!state.toolMode) {
            saveResult(conversationId, question, state.text.toString(), turn);
            return Flux.just(event("done", ""));
        }
        if (maxRounds > 0 && round >= maxRounds) {
            return forceFinalStream(client, messages, conversationId, question, turn);
        }
        messages.add(AssistantMessage.builder().content(state.text.toString()).toolCalls(state.toolCalls).build());
        AtomicReferenceArray<ToolOutcome> outcomes = new AtomicReferenceArray<>(state.toolCalls.size());
        return Flux.range(0, state.toolCalls.size())
                .flatMap(i -> {
                    AssistantMessage.ToolCall call = state.toolCalls.get(i);
                    return Flux.concat(Mono.just(event("thinking", toolStatus(call))),
                            Mono.fromRunnable(() -> outcomes.set(i, executeToolCall(call)))
                                    .subscribeOn(Schedulers.boundedElastic()).then(Mono.<ChatEvent>empty()));
                })
                .concatWith(Flux.defer(() -> {
                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(state.toolCalls.size());
                    for (int i = 0; i < state.toolCalls.size(); i++) {
                        AssistantMessage.ToolCall call = state.toolCalls.get(i);
                        ToolOutcome outcome = outcomes.get(i);
                        turn.tools.add(new ToolExecution(call.name(), Objects.toString(call.arguments(), ""), outcome.result()));
                        turn.references.addAll(outcome.references());
                        responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), outcome.result()));
                    }
                    messages.add(ToolResponseMessage.builder().responses(responses).build());
                    return scheduleRound(client, messages, conversationId, question, turn, round + 1);
                }));
    }

    private String toolStatus(AssistantMessage.ToolCall call) {
        if (!call.name().contains("tavily_search")) return "正在调用工具：" + call.name();
        try {
            String query = objectMapper.readTree(call.arguments()).path("query").asText("");
            return "正在搜索信息：" + query.substring(0, Math.min(query.length(), 120));
        } catch (Exception ex) {
            return "正在搜索信息";
        }
    }

    private ToolOutcome executeToolCall(AssistantMessage.ToolCall call) {
        for (ToolCallback tool : tools) {
            if (tool.getToolDefinition().name().equals(call.name())) {
                try {
                    String result = tool.call(call.arguments());
                    return new ToolOutcome(result, call.name().contains("tavily")
                            ? extractSourceLinks(result, objectMapper) : List.of());
                } catch (Exception ex) {
                    return new ToolOutcome("工具执行失败：" + ex.getClass().getSimpleName(), List.of());
                }
            }
        }
        return new ToolOutcome("工具未找到：" + call.name(), List.of());
    }

    private record ToolOutcome(String result, List<String> references) {
    }

    static List<String> extractSourceLinks(String result, ObjectMapper mapper) throws JsonProcessingException {
        String source = result.startsWith("\"") ? mapper.readTree(result).asText() : result;
        return SOURCE_URL.matcher(source).results().map(match -> match.group()).distinct().toList();
    }

    private Flux<ChatEvent> forceFinalStream(
            ChatClient client, List<Message> messages, String conversationId, String question, TurnState turn) {
        RoundState state = new RoundState();
        List<Message> finalMessages = new ArrayList<>(messages);
        finalMessages.add(new UserMessage("请基于已有信息直接回答，不再调用工具。"));
        return client.prompt().messages(finalMessages).stream().chatResponse()
                .concatMap(chunk -> processChunk(chunk, state, turn))
                .concatWith(Flux.defer(() -> {
                    saveResult(conversationId, question, state.text.toString(), turn);
                    return Flux.just(event("done", ""));
                }));
    }

    private void saveResult(String conversationId, String question, String answer, TurnState turn) {
        Long firstResponseMs = turn.firstResponseNanos == 0 ? null
                : TimeUnit.NANOSECONDS.toMillis(turn.firstResponseNanos - turn.startedNanos);
        conversationService.saveExchange(conversationId, question, new ConversationService.TurnResult(
                answer, turn.thinking.isEmpty() ? null : turn.thinking.toString(),
                objectMapper.valueToTree(turn.tools).toString(),
                objectMapper.valueToTree(turn.references).toString(),
                firstResponseMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - turn.startedNanos)));
    }

    private static ChatEvent event(String name, String data) {
        return new ChatEvent(name, data);
    }

    public record ChatEvent(String name, String data) {
    }

    static class RoundState {
        final List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        boolean toolMode;
    }

    private static class TurnState {
        final long startedNanos = System.nanoTime();
        final StringBuilder thinking = new StringBuilder();
        final List<ToolExecution> tools = new ArrayList<>();
        final List<String> references = new ArrayList<>();
        long firstResponseNanos;

        void markFirstResponse() {
            if (firstResponseNanos == 0) firstResponseNanos = System.nanoTime();
        }
    }

    private record ToolExecution(String name, String arguments, String result) {
    }

}
