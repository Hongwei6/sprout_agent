package com.sprout.agent.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.agents.BaseAgent.ChatEvent;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import com.sprout.agent.service.TestAgentTasks;
import com.sprout.agent.service.UploadedFileService;
import com.sprout.agent.tool.HostBashTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.mockito.Answers;
import reactor.core.publisher.Flux;

class SkillsReactAgentTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void skillsPromptRequestsChineseReasoningWithoutOverridingRequestedAnswerLanguage() {
        Fixture fixture = new Fixture(new ToolCallback[0], Flux.just(response(new AssistantMessage("完成"))));
        String prompt = fixture.agent.systemPrompt();
        assertTrue(prompt.contains("思考和推理过程（包括模型提供的思考内容）始终使用简体中文"));
        assertTrue(prompt.contains("即使用户要求其他语言回答"));
        assertTrue(prompt.contains("最终回答使用简体中文（用户明确要求其他语言时除外）"));
    }

    @Test
    void streamsToolBoundariesAndPersistsOrderedTimelineWithoutExposingPrivateCalls() throws Exception {
        ToolCallback good = tool("good", "private-result", null);
        ToolCallback bad = tool("bad", null, new IllegalStateException("private-failure"));
        ChatResponse calls = response(AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("one", "function", "good", "{\"token\":\"secret\"}"),
                new AssistantMessage.ToolCall("two", "function", "bad", "{\"token\":\"secret\"}"))).build());
        Fixture fixture = new Fixture(new ToolCallback[] {good, bad}, Flux.just(calls),
                Flux.just(response(new AssistantMessage("完成"))));
        org.mockito.Mockito.doAnswer(inv -> {
            assertTrue(fixture.events.stream().anyMatch(e -> e.name().equals("tool_start")
                    && e.data().contains("\"toolCallId\":\"one\"")));
            return "private-result";
        }).when(good).call(anyString());
        org.mockito.Mockito.doAnswer(inv -> {
            assertTrue(fixture.events.stream().anyMatch(e -> e.name().equals("tool_start")
                    && e.data().contains("\"toolCallId\":\"two\"")));
            throw new IllegalStateException("private-failure");
        }).when(bad).call(anyString());
        List<ChatEvent> events = fixture.stream();
        assertEquals(2, events.stream().filter(e -> e.name().equals("tool_start")).count());
        assertEquals(2, events.stream().filter(e -> e.name().equals("tool_end")).count());
        assertTrue(events.indexOf(events.stream().filter(e -> e.name().equals("complete")).findFirst().orElseThrow())
                < events.indexOf(events.stream().filter(e -> e.name().equals("done")).findFirst().orElseThrow()));
        assertFalse(events.stream().anyMatch(e -> e.name().equals("thinking")));
        assertFalse(events.stream().filter(e -> e.name().startsWith("tool_"))
                .anyMatch(e -> e.data().contains("secret") || e.data().contains("private")));
        assertEquals(List.of("error", "success"), events.stream().filter(e -> e.name().equals("tool_end"))
                .map(e -> {
                    try { return mapper.readTree(e.data()).path("status").asText(); }
                    catch (Exception ex) { throw new AssertionError(ex); }
                }).sorted().toList());
        JsonNode timeline = mapper.readTree(fixture.saved.get().toolsJson()).path("timeline");
        assertEquals(2, timeline.size());
        assertEquals("one", timeline.get(0).path("toolCallId").asText());
        assertEquals("two", timeline.get(1).path("toolCallId").asText());
        assertEquals("success", timeline.get(0).path("status").asText());
        assertEquals("error", timeline.get(1).path("status").asText());
        JsonNode savedCalls = mapper.readTree(fixture.saved.get().toolsJson()).path("calls");
        assertEquals(2, savedCalls.size());
        assertEquals("{\"token\":\"secret\"}", savedCalls.get(0).path("arguments").asText());
        assertFalse(timeline.toString().contains("secret") || timeline.toString().contains("private"));
    }

    @Test
    void reportsNonzeroCommandExitCodesAsFailedStages() throws Exception {
        ToolCallback bash = tool("bash", "Exit code 7:\ncommand not found", null);
        ToolCallback python = tool("run_python", "退出码 2：\nTraceback", null);
        ToolCallback script = tool("run_skill_script", "退出码 0：\nready", null);
        ChatResponse calls = response(AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("shell", "function", "bash", "{}"),
                new AssistantMessage.ToolCall("code", "function", "run_python", "{}"),
                new AssistantMessage.ToolCall("script", "function", "run_skill_script", "{}"))).build());
        Fixture fixture = new Fixture(new ToolCallback[] {bash, python, script}, Flux.just(calls),
                Flux.just(response(new AssistantMessage("处理完毕"))));

        List<ChatEvent> events = fixture.stream();
        List<String> statuses = events.stream().filter(e -> e.name().equals("tool_end")).map(e -> {
            try { return mapper.readTree(e.data()).path("status").asText(); }
            catch (Exception ex) { throw new AssertionError(ex); }
        }).toList();
        assertEquals(3, statuses.size());
        assertEquals(2, statuses.stream().filter("error"::equals).count());
        assertEquals(1, statuses.stream().filter("success"::equals).count());
        assertEquals(List.of("error", "error", "success"), mapper.readTree(fixture.saved.get().toolsJson())
                .path("timeline").findValuesAsText("status"));
    }

    @Test
    void failedHostCommandAtRoundLimitCanRecoverAndGenerateFile() throws Exception {
        String failed = mapper.writeValueAsString(java.util.Map.of(
                "command", "false", "workingDirectory", directory.toString()));
        String corrected = mapper.writeValueAsString(java.util.Map.of(
                "command", "printf 'generated' > result.txt", "workingDirectory", directory.toString()));
        Fixture fixture = new Fixture(1, ToolCallbacks.from(new HostBashTool()),
                Flux.just(response(AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("fail", "function", "bash", failed))).build())),
                Flux.just(response(AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("fix", "function", "bash", corrected))).build())),
                Flux.just(response(new AssistantMessage("文件已生成"))));

        List<ChatEvent> events = fixture.stream();
        assertEquals("generated", Files.readString(directory.resolve("result.txt")));
        assertEquals(3, fixture.modelCalls.get());
        assertEquals(List.of("error", "success"), mapper.readTree(fixture.saved.get().toolsJson())
                .path("timeline").findValuesAsText("status"));
        assertEquals("文件已生成", fixture.saved.get().answer());
        assertEquals(List.of("complete", "done"), events.subList(events.size() - 2, events.size())
                .stream().map(ChatEvent::name).toList());
    }

    @Test
    void failedCommandCanBeAssessedAsUnrecoverableWithoutBlindRetry() throws Exception {
        ToolCallback bash = tool("bash", "Exit code 127:\ncommand not found", null);
        Fixture fixture = new Fixture(1, new ToolCallback[] {bash},
                Flux.just(response(AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("fail", "function", "bash", "{}"))).build())),
                Flux.just(response(new AssistantMessage("依赖不可用，尚未生成文件"))));

        List<ChatEvent> events = fixture.stream();
        assertEquals(2, fixture.modelCalls.get());
        org.mockito.Mockito.verify(bash).call("{}");
        assertEquals("依赖不可用，尚未生成文件", fixture.saved.get().answer());
        assertEquals("done", events.getLast().name());
    }

    @Test
    void retriesOnlyBeforeVisibleOutputAndSignalsEachRetry() throws Exception {
        Fixture fixture = new Fixture(new ToolCallback[0], Flux.error(new IllegalStateException("private")),
                Flux.error(new IllegalStateException("private")),
                Flux.just(response(new AssistantMessage("完成"))));
        List<ChatEvent> events = fixture.stream();
        assertEquals(2, events.stream().filter(e -> e.name().equals("stage_error")).count());
        for (ChatEvent event : events) {
            if (event.name().equals("stage_error")) {
                assertEquals("LLM_RETRY", mapper.readTree(event.data()).path("code").asText());
                assertFalse(event.data().contains("private"));
            }
        }
        assertEquals(3, fixture.modelCalls.get());
        assertEquals("done", events.getLast().name());
        JsonNode retryTimeline = mapper.readTree(fixture.saved.get().toolsJson()).path("timeline");
        assertEquals(2, retryTimeline.size());
        assertEquals("error", retryTimeline.get(0).path("type").asText());

        Fixture partial = new Fixture(new ToolCallback[0], Flux.concat(
                Flux.just(response(new AssistantMessage("visible"))),
                Flux.error(new IllegalStateException("private"))));
        List<ChatEvent> failed = partial.stream();
        assertEquals(List.of("conversation", "delta", "error"), failed.stream().map(ChatEvent::name).toList());
        assertEquals(1, partial.modelCalls.get());

        Fixture exhausted = new Fixture(new ToolCallback[0],
                Flux.error(new IllegalStateException("private")),
                Flux.error(new IllegalStateException("private")),
                Flux.error(new IllegalStateException("private")),
                Flux.error(new IllegalStateException("private")));
        List<ChatEvent> terminal = exhausted.stream();
        assertEquals(3, terminal.stream().filter(e -> e.name().equals("stage_error")).count());
        assertEquals("error", terminal.getLast().name());
        assertFalse(terminal.stream().anyMatch(e -> e.name().equals("complete") || e.name().equals("done")));
    }

    @Test
    void stoppingDuringToolPersistsInterruptedTimeline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolCallback callback = tool("slow", "result", null);
        org.mockito.Mockito.doAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "result";
        }).when(callback).call(anyString());
        Fixture fixture = new Fixture(new ToolCallback[] {callback}, Flux.just(response(
                AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("slow-1", "function", "slow", "{}"))).build())));
        fixture.agent.stream(null, "问题").subscribe(fixture.events::add);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(fixture.tasks.stopTask("session"));
            JsonNode timeline = mapper.readTree(fixture.saved.get().toolsJson()).path("timeline");
            assertEquals("error", timeline.get(0).path("status").asText());
            assertEquals("error", timeline.get(1).path("type").asText());
            assertFalse(fixture.events.stream().anyMatch(e -> e.name().equals("complete")));
        } finally {
            release.countDown();
        }
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ToolCallback tool(String name, String result, RuntimeException failure) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        if (failure == null) when(callback.call(anyString())).thenReturn(result);
        else when(callback.call(anyString())).thenThrow(failure);
        return callback;
    }

    private class Fixture {
        final AgentTaskManager tasks = TestAgentTasks.newManager();
        final AtomicReference<ConversationService.TurnResult> saved = new AtomicReference<>();
        final AtomicInteger modelCalls = new AtomicInteger();
        final java.util.concurrent.CopyOnWriteArrayList<ChatEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final SkillsReactAgent agent;

        @SuppressWarnings("unchecked")
        @SafeVarargs
        Fixture(ToolCallback[] callbacks, Flux<ChatResponse>... responses) {
            this(5, callbacks, responses);
        }

        @SuppressWarnings("unchecked")
        @SafeVarargs
        Fixture(int maxRounds, ToolCallback[] callbacks, Flux<ChatResponse>... responses) {
            ConversationService conversations = mock(ConversationService.class);
            when(conversations.createConversation(eq(null), anyString())).thenReturn("session");
            ChatMemory memory = mock(ChatMemory.class);
            when(memory.get("session")).thenReturn(List.of());
            when(conversations.createPersistentChatMemory("session", 20)).thenReturn(memory);
            org.mockito.Mockito.doAnswer(inv -> {
                saved.set(inv.getArgument(2));
                return null;
            }).when(conversations).saveExchange(eq("session"), anyString(), any(ConversationService.TurnResult.class),
                    eq(null));
            ObjectProvider<ChatClient.Builder> builders = mock(ObjectProvider.class);
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            ChatClient client = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class, Answers.RETURNS_SELF);
            ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
            when(builders.getObject()).thenReturn(builder);
            when(builder.build()).thenReturn(client);
            when(client.prompt()).thenReturn(spec);
            when(spec.stream()).thenReturn(stream);
            when(stream.chatResponse()).thenAnswer(inv -> responses[modelCalls.getAndIncrement()]);
            SyncMcpToolCallbackProvider search = mock(SyncMcpToolCallbackProvider.class);
            when(search.getToolCallbacks()).thenReturn(callbacks);
            agent = new SkillsReactAgent(builders, conversations, tasks, mock(UploadedFileService.class), mapper,
                    search, new SkillRegistry(directory), mock(SkillInstaller.class), "api-key", "model",
                    maxRounds, 20, false, 60000, 4, 200);
        }

        List<ChatEvent> stream() {
            return agent.stream(null, "问题").doOnNext(events::add).collectList().block(Duration.ofSeconds(45));
        }
    }
}
