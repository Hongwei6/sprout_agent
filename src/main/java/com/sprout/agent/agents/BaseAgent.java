package com.sprout.agent.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.sprout.agent.dto.SearchResult;
import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import com.sprout.agent.service.UploadedFileService;
import com.sprout.agent.prompts.ReactAgentPrompts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

public abstract class BaseAgent {

    private static final String LOCAL_API_KEY_PLACEHOLDER = "local-placeholder";
    private static final int MAX_FAILED_TOOL_RECOVERY_ROUNDS = 6;

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final AgentTaskManager taskManager;
    private final UploadedFileService fileService;
    private final ToolCallback[] tools;
    private final String apiKey;
    private final String model;
    private final int maxRounds;
    private final int maxMessages;
    private final boolean enableRecommendations;

    protected BaseAgent(
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            ConversationService conversationService,
            AgentTaskManager taskManager,
            UploadedFileService fileService,
            ObjectMapper objectMapper,
            SyncMcpToolCallbackProvider toolProvider,
            String apiKey,
            String model,
            int maxRounds,
            int maxMessages,
            boolean enableRecommendations) {
        this.chatClientBuilder = chatClientBuilder;
        this.conversationService = conversationService;
        this.taskManager = taskManager;
        this.objectMapper = objectMapper;
        this.fileService = fileService;
        this.tools = toolProvider == null ? new ToolCallback[0] : toolProvider.getToolCallbacks();
        this.apiKey = apiKey;
        this.model = model;
        this.maxRounds = maxRounds;
        if (maxMessages < 2 || maxMessages % 2 != 0) {
            throw new IllegalArgumentException("sprout.agent.max-messages must be a positive even number");
        }
        this.maxMessages = maxMessages;
        this.enableRecommendations = enableRecommendations;
    }

    public boolean isAvailable() {
        return !apiKey.isBlank() && !LOCAL_API_KEY_PLACEHOLDER.equals(apiKey)
                && !model.isBlank() && chatClientBuilder.getIfAvailable() != null;
    }

    protected Flux<ChatEvent> streamAgent(String requestedConversationId, String question, String fileId) {
        return Flux.defer(() -> {
            UploadedFileEntity file = fileId == null ? null : fileService.requireFile(fileId);
            String conversationId = conversationService.createConversation(requestedConversationId, question);
            Sinks.Many<ChatEvent> sink = Sinks.many().unicast()
                    .onBackpressureBuffer(new ArrayBlockingQueue<>(256));
            AgentTaskManager.TaskInfo task = taskManager.registerTask(conversationId, sink, "react");
            if (task == null) return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
            TurnState turn = new TurnState(file, toolCallbacks(file), stagedEvents());
            task.onStop(() -> {
                synchronized (turn) {
                    if (turn.saved) return;
                    if (turn.staged) {
                        for (int i = 0; i < turn.timeline.size(); i++) {
                            Map<String, String> entry = turn.timeline.get(i);
                            if ("tool".equals(entry.get("type")) && "running".equals(entry.get("status"))) {
                                turn.timeline.set(i, Map.of("type", "tool", "toolCallId", entry.get("toolCallId"),
                                        "toolName", entry.get("toolName"), "status", "error"));
                            }
                        }
                        turn.timeline.add(Map.of("type", "error", "message", "已停止生成"));
                    }
                    String partial = turn.partialAnswer();
                    conversationService.saveExchange(conversationId, question, new ConversationService.TurnResult(
                            partial.isBlank() ? "（回答已停止）" : partial,
                            turn.thinking.isEmpty() ? null : turn.thinking.toString(),
                            turn.toolsJson(objectMapper),
                            objectMapper.valueToTree(turn.searchResults.values()).toString(),
                            null, null, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - turn.startedNanos)), file);
                    turn.saved = true;
                }
            });
            return sink.asFlux()
                    .doOnSubscribe(subscription -> {
                        Disposable execution = Flux.defer(() -> {
                            ChatMemory memory = conversationService.createPersistentChatMemory(conversationId, maxMessages);
                            List<Message> messages = new ArrayList<>(memory.get(conversationId));
                            messages.add(0, new SystemMessage(systemPrompt()));
                            Flux<ChatEvent> answer = Flux.defer(() -> {
                                messages.add(prepareUserMessage(question, file));
                                return scheduleRound(chatClientBuilder.getObject().build(), messages,
                                        conversationId, question, turn, 1);
                            });
                            return Flux.just(event("conversation", conversationId))
                                    .concatWith(file != null && file.getContentType().startsWith("image/")
                                            && (file.getExtractedText() == null || file.getExtractedText().isBlank())
                                            ? Flux.just(event("thinking", "正在识别图片")).concatWith(answer) : answer);
                        }).subscribeOn(Schedulers.boundedElastic()).subscribe(
                                event -> taskManager.emit(conversationId, task, event),
                                ex -> {
                                    if (taskManager.removeTask(conversationId, task)) {
                                        sink.tryEmitNext(event("error", "对话生成失败，请稍后重试。"));
                                        sink.tryEmitComplete();
                                    }
                                },
                                () -> {
                                    if (taskManager.removeTask(conversationId, task)) sink.tryEmitComplete();
                                });
                        task.setDisposable(execution);
                    })
                    .doOnCancel(() -> {
                        taskManager.removeTask(conversationId, task);
                        task.cancel();
                    });
        });
    }
    protected abstract String systemPrompt();

    protected abstract UserMessage prepareUserMessage(String question, UploadedFileEntity file);

    protected ToolCallback[] toolCallbacks(UploadedFileEntity file) {
        return tools;
    }
    protected boolean stagedEvents() {
        return false;
    }

    private Flux<ChatEvent> scheduleRound(
            ChatClient client, List<Message> messages, String conversationId, String question, TurnState turn, int round) {
        RoundState state = new RoundState();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().internalToolExecutionEnabled(false);
        Flux<ChatResponse> chunks = Flux.defer(() -> client.prompt().messages(messages)
                .options(options.build())
                .toolCallbacks(turn.availableTools)
                .stream().chatResponse());
        return streamModel(chunks, state, turn, 0)
                .concatWith(Flux.defer(() -> finishRound(client, messages, conversationId, question, turn, round, state))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private Flux<ChatEvent> streamModel(Flux<ChatResponse> chunks, RoundState state, TurnState turn, int retries) {
        return chunks.concatMap(chunk -> processChunk(chunk, state, turn))
                .onErrorResume(ex -> {
                    if (!turn.staged || retries >= 3 || state.text.length() > 0
                            || !turn.partialAnswer().isEmpty()) return Flux.error(ex);
                    state.toolCalls.clear();
                    state.toolMode = false;
                    String message = "模型响应中断，10 秒后重试（" + (retries + 1) + "/3）";
                    turn.addTimeline(Map.of("type", "error", "message", message));
                    return Flux.just(event("stage_error", objectMapper.valueToTree(Map.of(
                            "code", "LLM_RETRY", "message", message)).toString()))
                            .concatWith(Mono.delay(Duration.ofSeconds(10))
                                    .thenMany(Flux.defer(() -> streamModel(chunks, state, turn, retries + 1))));
                });
    }

    private Flux<ChatEvent> processChunk(ChatResponse chunk, RoundState state, TurnState turn) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return Flux.empty();
        AssistantMessage output = chunk.getResult().getOutput();
        Object reasoning = output.getMetadata().get("reasoningContent");
        Flux<ChatEvent> reasoningEvents = Flux.empty();
        if (reasoning instanceof String text && !text.isEmpty()) {
            turn.addTimeline(Map.of("type", "thinking", "content", text));
            turn.thinking.append(text);
            reasoningEvents = Flux.just(event("reasoning", text));
        }
        if (output.hasToolCalls() || (output.getText() != null && !output.getText().isEmpty())
                || (reasoning instanceof String text && !text.isEmpty())) {
            turn.markFirstResponse();
        }
        if (output.hasToolCalls()) {
            state.toolMode = true;
            for (AssistantMessage.ToolCall call : output.getToolCalls()) mergeToolCall(state, call);
            return reasoningEvents;
        }
        String text = output.getText();
        if (text == null || text.isEmpty() || state.toolMode || (turn.file != null && !turn.fileToolSucceeded))
            return reasoningEvents;
        state.text.append(text);
        turn.appendAnswer(text);
        return reasoningEvents.concatWithValues(event("delta", text));
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
            return finishAnswer(conversationId, question,
                    turn.file != null && !turn.fileToolSucceeded ? "未能读取附件内容，无法基于文件回答。" : state.text.toString(),
                    turn);
        }
        messages.add(AssistantMessage.builder().content(state.text.toString()).toolCalls(state.toolCalls).build());
        AtomicReferenceArray<ToolOutcome> outcomes = new AtomicReferenceArray<>(state.toolCalls.size());
        return Flux.range(0, state.toolCalls.size())
                .flatMap(i -> {
                    AssistantMessage.ToolCall call = state.toolCalls.get(i);
                    if (!turn.staged) {
                        return Flux.concat(Mono.just(event("thinking", toolStatus(call))),
                                Mono.fromRunnable(() -> outcomes.set(i, executeToolCall(call, turn.availableTools)))
                                        .subscribeOn(Schedulers.boundedElastic()).then(Mono.<ChatEvent>empty()));
                    }
                    return Flux.defer(() -> {
                        String id = Objects.toString(call.id(), "");
                        int entry = turn.startTool(id, call.name());
                        return Flux.concat(
                                Mono.just(event("tool_start", objectMapper.valueToTree(Map.of(
                                        "toolCallId", id, "toolName", call.name())).toString())),
                                Mono.fromCallable(() -> executeToolCall(call, turn.availableTools))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .onErrorReturn(new ToolOutcome("工具执行失败", List.of(), false))
                                        .map(outcome -> {
                                            outcomes.set(i, outcome);
                                            turn.endTool(entry, id, call.name(), outcome.success());
                                            return event("tool_end", objectMapper.valueToTree(Map.of(
                                                    "toolCallId", id, "toolName", call.name(),
                                                    "status", outcome.success() ? "success" : "error")).toString());
                                        }));
                    });
                })
                .concatWith(Flux.defer(() -> {
                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(state.toolCalls.size());
                    for (int i = 0; i < state.toolCalls.size(); i++) {
                        AssistantMessage.ToolCall call = state.toolCalls.get(i);
                        ToolOutcome outcome = outcomes.get(i);
                        if (!outcome.success()) turn.failedToolRounds++;
                        if (turn.file != null && call.name().equals("loadContent")
                                && outcome.success() && !outcome.result().isBlank()) turn.fileToolSucceeded = true;
                        turn.tools.add(new ToolExecution(call.name(),
                                Objects.toString(call.arguments(), ""), outcome.result()));
                        for (SearchResult reference : outcome.references()) {
                            turn.searchResults.putIfAbsent(reference.url(), reference);
                        }
                        responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), outcome.result()));
                    }
                    messages.add(ToolResponseMessage.builder().responses(responses).build());
                    return maxRounds > 0 && round >= maxRounds
                            + Math.min(turn.failedToolRounds, MAX_FAILED_TOOL_RECOVERY_ROUNDS)
                            ? turn.file == null || turn.fileToolSucceeded
                                    ? forceFinalStream(client, messages, conversationId, question, turn)
                                    : finishAnswer(conversationId, question, "未能读取附件内容，无法基于文件回答。", turn)
                            : scheduleRound(client, messages, conversationId, question, turn, round + 1);
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

    private ToolOutcome executeToolCall(AssistantMessage.ToolCall call, ToolCallback[] availableTools) {
        for (ToolCallback tool : availableTools) {
            if (tool.getToolDefinition().name().equals(call.name())) {
                try {
                    String result = tool.call(call.arguments());
                    if (result != null && result.startsWith("\"")
                            && (call.name().equals("bash") || call.name().equals("run_python")
                                    || call.name().equals("run_skill_script"))) {
                        result = objectMapper.readTree(result).asText();
                    }
                    boolean success = switch (call.name()) {
                        case "bash" -> result != null && result.startsWith("Exit code 0:\n");
                        case "run_python", "run_skill_script" -> result != null && result.startsWith("退出码 0：\n");
                        default -> true;
                    };
                    return new ToolOutcome(result, call.name().contains("tavily_search")
                            ? parseSearchResults(result, objectMapper) : List.of(), success);
                } catch (Exception ex) {
                    return new ToolOutcome("工具执行失败：" + ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : "：" + ex.getMessage()), List.of(), false);
                }
            }
        }
        return new ToolOutcome("工具未找到：" + call.name(), List.of(), false);
    }

    private record ToolOutcome(String result, List<SearchResult> references, boolean success) {
    }

    static List<SearchResult> parseSearchResults(String result, ObjectMapper mapper) throws JsonProcessingException {
        List<SearchResult> references = new ArrayList<>();
        collectSearchResults(mapper.readTree(result), mapper, references);
        return references;
    }

    private static void collectSearchResults(JsonNode node, ObjectMapper mapper, List<SearchResult> references)
            throws JsonProcessingException {
        if (node.isArray()) {
            for (JsonNode item : node) collectSearchResults(item, mapper, references);
            return;
        }
        if (!node.isObject()) return;
        JsonNode results = node.path("results");
        if (results.isArray()) {
            for (JsonNode item : results) {
                String url = item.path("url").asText("");
                if (!url.startsWith("https://") && !url.startsWith("http://")) continue;
                String content = item.path("content").asText("").strip();
                references.add(new SearchResult(url, item.path("title").asText(""),
                        content.substring(0, Math.min(content.length(), 300))));
            }
        }
        JsonNode text = node.path("text");
        if (text.isTextual()) {
            String value = text.asText().stripLeading();
            if (value.startsWith("{") || value.startsWith("[")) {
                collectSearchResults(mapper.readTree(value), mapper, references);
            }
        }
        JsonNode content = node.path("content");
        if (content.isArray()) collectSearchResults(content, mapper, references);
    }

    private Flux<ChatEvent> forceFinalStream(
            ChatClient client, List<Message> messages, String conversationId, String question, TurnState turn) {
        RoundState state = new RoundState();
        List<Message> finalMessages = new ArrayList<>(messages);
        finalMessages.add(new UserMessage("请基于已有信息直接回答，不再调用工具。"));
        Flux<ChatResponse> chunks = Flux.defer(() -> client.prompt().messages(finalMessages).stream().chatResponse());
        return streamModel(chunks, state, turn, 0)
                .concatWith(Flux.defer(() -> finishAnswer(conversationId, question, state.text.toString(), turn)));
    }

    private Flux<ChatEvent> finishAnswer(String conversationId, String question, String answer, TurnState turn) {
        String referencesJson = objectMapper.valueToTree(turn.searchResults.values()).toString();
        String recommendationsJson = generateRecommendations(conversationId, question, answer);
        Long firstResponseMs = turn.firstResponseNanos == 0 ? null
                : TimeUnit.NANOSECONDS.toMillis(turn.firstResponseNanos - turn.startedNanos);
        synchronized (turn) {
            if (turn.saved) return Flux.empty();
            conversationService.saveExchange(conversationId, question, new ConversationService.TurnResult(
                    answer, turn.thinking.isEmpty() ? null : turn.thinking.toString(),
                    turn.toolsJson(objectMapper), referencesJson, recommendationsJson,
                    firstResponseMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - turn.startedNanos)), turn.file);
            turn.saved = true;
        }
        List<ChatEvent> events = new ArrayList<>(3);
        if (!turn.searchResults.isEmpty()) {
            events.add(event("reference", objectMapper.valueToTree(
                    new ReferenceResponse("reference", referencesJson, turn.searchResults.size())).toString()));
        }
        if (recommendationsJson != null) {
            events.add(event("recommend", "{\"type\":\"recommend\",\"content\":" + recommendationsJson + "}"));
        }
        if (turn.staged) events.add(event("complete", ""));
        events.add(event("done", ""));
        return Flux.fromIterable(events);
    }

    private String generateRecommendations(String conversationId, String question, String answer) {
        if (!enableRecommendations || answer.isBlank()) return null;
        try {
            List<Message> context = new ArrayList<>(conversationService
                    .createPersistentChatMemory(conversationId, maxMessages).get(conversationId));
            context.add(0, new SystemMessage(ReactAgentPrompts.getRecommendPrompt()));
            context.add(new UserMessage(question));
            context.add(new AssistantMessage(answer));
            context.add(new UserMessage("请生成恰好3个后续问题，仅输出 JSON 字符串数组，不要 Markdown 或解释。"));
            String result = chatClientBuilder.getObject().build().prompt().messages(context).call().content();
            List<String> questions = parseRecommendations(result);
            return questions.size() == 3 && questions.stream().allMatch(s -> s != null && !s.isBlank())
                    && questions.stream().distinct().count() == 3 ? objectMapper.writeValueAsString(questions) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> parseRecommendations(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, new TypeReference<List<String>>() {});
    }



    private record ReferenceResponse(String type, String content, int count) {
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
        final UploadedFileEntity file;
        final ToolCallback[] availableTools;
        boolean fileToolSucceeded;
        int failedToolRounds;

        final boolean staged;

        TurnState(UploadedFileEntity file, ToolCallback[] availableTools, boolean staged) {
            this.file = file;
            this.availableTools = availableTools;
            this.staged = staged;
        }
        final StringBuilder thinking = new StringBuilder();
        final List<ToolExecution> tools = new ArrayList<>();
        final List<Map<String, String>> timeline = new ArrayList<>();
        final LinkedHashMap<String, SearchResult> searchResults = new LinkedHashMap<>();
        boolean saved;
        final StringBuilder partialAnswer = new StringBuilder();

        synchronized void appendAnswer(String text) { partialAnswer.append(text); }
        synchronized String partialAnswer() { return partialAnswer.toString(); }
        long firstResponseNanos;
        synchronized void addTimeline(Map<String, String> entry) {
            if (staged && !saved) timeline.add(entry);
        }

        synchronized int startTool(String id, String name) {
            if (saved) return -1;
            timeline.add(Map.of("type", "tool", "toolCallId", id, "toolName", name, "status", "running"));
            return timeline.size() - 1;
        }

        synchronized void endTool(int entry, String id, String name, boolean success) {
            if (!saved && entry >= 0) timeline.set(entry, Map.of("type", "tool",
                    "toolCallId", id, "toolName", name, "status", success ? "success" : "error"));
        }

        String toolsJson(ObjectMapper mapper) {
            return mapper.valueToTree(staged ? Map.of("timeline", timeline, "calls", tools) : tools).toString();
        }

        void markFirstResponse() {
            if (firstResponseNanos == 0) firstResponseNanos = System.nanoTime();
        }
    }

    private record ToolExecution(String name, String arguments, String result) {
    }

}
