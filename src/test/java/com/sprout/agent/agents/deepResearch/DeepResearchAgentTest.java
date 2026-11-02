package com.sprout.agent.agents.deepResearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.agents.BaseAgent.ChatEvent;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

class DeepResearchAgentTest {
    private static final String ID = "a".repeat(32);
    private static final String QUESTION = "研究人工智能行业";

    @Test
    void rejectsMalformedAndDuplicatePlansAndParsesActualMcpReferences() throws Exception {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> fixture.agent.parsePlan("[]"));
        assertThrows(IllegalArgumentException.class, () -> fixture.agent.parsePlan(
                "[{\"id\":\"x\",\"order\":1,\"instruction\":\"a\"},"
                        + "{\"id\":\"x\",\"order\":2,\"instruction\":\"b\"}]"));
        assertThrows(IllegalArgumentException.class, () -> fixture.agent.parsePlan(
                "[{\"id\":\"x\",\"order\":0,\"instruction\":\"a\"}]"));
        assertEquals(2, fixture.agent.parsePlan(
                "[{\"id\":\"a\",\"order\":2,\"instruction\":\"后续搜索\"},"
                        + "{\"id\":\"b\",\"order\":1,\"instruction\":\"先行搜索\"}]").size());
        assertEquals("https://example.org/verified", fixture.agent.parseReferences(
                "[{\"text\":\"{\\\"results\\\":[{\\\"url\\\":\\\"https://example.org/verified\\\","
                        + "\\\"title\\\":\\\"可信来源\\\",\\\"content\\\":\\\"资料\\\"},"
                        + "{\\\"url\\\":\\\"javascript:bad\\\"}]}\"}]").getFirst().url());
    }

    @Test
    void clarificationPausesWithVisibleAnswerAndPersistedMode() {
        Fixture fixture = new Fixture();
        fixture.clarification = "【需要补充信息】请说明研究对象？";
        List<ChatEvent> events = fixture.agent.stream(null, QUESTION).collectList().block(Duration.ofSeconds(5));
        assertEquals("conversation", events.getFirst().name());
        assertEquals("done", events.getLast().name());
        assertTrue(events.stream().anyMatch(event -> event.name().equals("delta")
                && event.data().contains("请说明研究对象")));
        assertEquals(0, fixture.plans.get());
        var saved = ArgumentCaptor.forClass(ConversationService.TurnResult.class);
        verify(fixture.conversations).saveExchange(eq(ID), eq(QUESTION), saved.capture());
        assertTrue(saved.getValue().thinking().startsWith("深度研究进度\n"));
        assertTrue(saved.getValue().answer().contains("暂停深入研究"));
    }

    @Test
    void ordersDependentGroupsAndReplansWithCritiqueFeedbackAndRealReferences() {
        Fixture fixture = new Fixture();
        fixture.firstPlan = "[{\"id\":\"later\",\"order\":2,\"instruction\":\"查证后续\"},"
                + "{\"id\":\"first\",\"order\":1,\"instruction\":\"查证先行\"},"
                + "{\"id\":\"parallel\",\"order\":1,\"instruction\":\"查证并行\"}]";
        fixture.secondPlan = "[{\"id\":\"replan\",\"order\":1,\"instruction\":\"补充新证据\"}]";
        List<ChatEvent> events = fixture.agent.stream(null, QUESTION).collectList().block(Duration.ofSeconds(5));
        assertEquals("done", events.getLast().name());
        assertEquals(2, fixture.plans.get());
        assertEquals(2, fixture.critiques.get());
        assertTrue(fixture.secondPlanContext.get().contains("缺少新证据"));
        assertTrue(fixture.secondPlanContext.get().contains("https://example.org/first"));
        assertTrue(fixture.dependentContext.get().contains("https://example.org/first"));
        assertTrue(fixture.dependentContext.get().contains("https://example.org/parallel"));
        assertTrue(fixture.toolQueries.indexOf("查证后续") > fixture.toolQueries.indexOf("查证先行"));
        assertTrue(fixture.toolQueries.indexOf("查证后续") > fixture.toolQueries.indexOf("查证并行"));
        List<String> progress = events.stream().filter(event -> event.name().equals("thinking"))
                .map(ChatEvent::data).toList();
        assertTrue(progress.stream().anyMatch(text -> text.contains("正在执行第 1 组检索")));
        assertTrue(progress.stream().anyMatch(text -> text.contains("第 1 组检索完成")));
        assertTrue(progress.stream().anyMatch(text -> text.contains("正在执行第 2 组检索")));
        assertTrue(progress.stream().anyMatch(text -> text.contains("任务 later 检索完成")));
        assertFalse(progress.stream().anyMatch(text -> text.contains("已核实的事实")));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("reference")
                && event.data().contains("https://example.org/replan")));
        var saved = ArgumentCaptor.forClass(ConversationService.TurnResult.class);
        verify(fixture.conversations).saveExchange(eq(ID), eq(QUESTION), saved.capture());
        assertTrue(saved.getValue().referencesJson().contains("https://example.org/first"));
        assertEquals("报告正文", saved.getValue().answer());
    }

    @Test
    void missingSearchResultsNeverProduceInventedReportOrReferences() {
        Fixture fixture = new Fixture();
        fixture.noSources = true;
        List<ChatEvent> events = fixture.agent.stream(null, QUESTION).collectList().block(Duration.ofSeconds(5));
        assertEquals("done", events.getLast().name());
        assertFalse(events.stream().anyMatch(event -> event.name().equals("reference")));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("delta")
                && event.data().contains("未能获得可引用的 Tavily 搜索资料")));
        var saved = ArgumentCaptor.forClass(ConversationService.TurnResult.class);
        verify(fixture.conversations).saveExchange(eq(ID), eq(QUESTION), saved.capture());
        assertEquals("[]", saved.getValue().referencesJson());
    }

    @Test
    void limitsParallelSearchesToThreePerOrderGroup() {
        Fixture fixture = new Fixture();
        fixture.firstPlan = "[{\"id\":\"1\",\"order\":1,\"instruction\":\"搜索一\"},"
                + "{\"id\":\"2\",\"order\":1,\"instruction\":\"搜索二\"},"
                + "{\"id\":\"3\",\"order\":1,\"instruction\":\"搜索三\"},"
                + "{\"id\":\"4\",\"order\":1,\"instruction\":\"搜索四\"}]";
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch firstThree = new CountDownLatch(3);
        fixture.beforeTool = () -> {
            int active = running.incrementAndGet();
            peak.accumulateAndGet(active, Math::max);
            firstThree.countDown();
            try { firstThree.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            running.decrementAndGet();
        };
        List<ChatEvent> events = fixture.agent.stream(null, QUESTION).collectList().block(Duration.ofSeconds(8));
        assertEquals("done", events.getLast().name());
        assertEquals(4, fixture.toolQueries.size());
        assertEquals(3, peak.get());
    }

    @Test
    void compressesWorkContextWithoutLosingOriginalReferences() {
        Fixture fixture = new Fixture(1000);
        fixture.sourceContent = "已核实的事实".repeat(100);
        fixture.firstPlan = "[{\"id\":\"1\",\"order\":1,\"instruction\":\"搜索一\"},"
                + "{\"id\":\"2\",\"order\":1,\"instruction\":\"搜索二\"},"
                + "{\"id\":\"3\",\"order\":1,\"instruction\":\"搜索三\"}]";
        List<ChatEvent> events = fixture.agent.stream(null, QUESTION).collectList().block(Duration.ofSeconds(5));
        assertEquals("done", events.getLast().name());
        assertTrue(fixture.secondPlanContext.get().contains("工作上下文摘要"));
        assertTrue(events.stream().anyMatch(event -> event.name().equals("thinking")
                && event.data().contains("压缩工作上下文")));
        var saved = ArgumentCaptor.forClass(ConversationService.TurnResult.class);
        verify(fixture.conversations).saveExchange(eq(ID), eq(QUESTION), saved.capture());
        assertTrue(saved.getValue().referencesJson().contains("https://example.org/replan"));
    }

    @Test
    void stopsBlockedSearchWithoutCompletedReportAndPersistsPartialTurn() throws Exception {
        Fixture fixture = new Fixture();
        fixture.firstPlan = "[{\"id\":\"first\",\"order\":1,\"instruction\":\"查证先行\"}]";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        fixture.beforeTool = () -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        };
        List<ChatEvent> events = new CopyOnWriteArrayList<>();
        fixture.agent.stream(null, QUESTION).subscribe(events::add);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(fixture.tasks.stopTask(ID));
        release.countDown();
        assertTrue(events.stream().anyMatch(event -> event.name().equals("stopped")));
        assertFalse(events.stream().anyMatch(event -> event.name().equals("done")));
        var saved = ArgumentCaptor.forClass(ConversationService.TurnResult.class);
        verify(fixture.conversations).saveExchange(eq(ID), eq(QUESTION), saved.capture());
        assertEquals("（研究已停止）", saved.getValue().answer());
        assertTrue(saved.getValue().thinking().startsWith("深度研究进度\n"));
    }

    private static final class Fixture {
        final ConversationService conversations = mock(ConversationService.class);
        final AgentTaskManager tasks = com.sprout.agent.service.TestAgentTasks.newManager();
        final AtomicInteger plans = new AtomicInteger();
        final AtomicInteger critiques = new AtomicInteger();
        final AtomicReference<String> secondPlanContext = new AtomicReference<>("");
        final AtomicReference<String> dependentContext = new AtomicReference<>("");
        final List<String> toolQueries = new CopyOnWriteArrayList<>();
        final DeepResearchAgent agent;
        String clarification = "【开始研究】研究 AI 行业";
        String firstPlan = "[{\"id\":\"first\",\"order\":1,\"instruction\":\"查证先行\"}]";
        String secondPlan = "[{\"id\":null,\"order\":0,\"instruction\":\"无需搜索\"}]";
        Runnable beforeTool = () -> {};
        boolean noSources;
        String sourceContent = "已核实的事实";

        Fixture() { this(50000); }

        Fixture(int contextLimit) {
            ObjectProvider<ChatClient.Builder> builders = mock(ObjectProvider.class);
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            ChatClient client = mock(ChatClient.class);
            when(builders.getObject()).thenReturn(builder);
            when(builder.build()).thenReturn(client);
            when(conversations.createConversation(null, QUESTION)).thenReturn(ID);
            ChatMemory memory = mock(ChatMemory.class);
            when(conversations.createPersistentChatMemory(ID, 20)).thenReturn(memory);
            when(memory.get(ID)).thenReturn(List.of());
            ToolCallback tool = mock(ToolCallback.class);
            ToolDefinition definition = mock(ToolDefinition.class);
            when(definition.name()).thenReturn("tavily_search");
            when(tool.getToolDefinition()).thenReturn(definition);
            when(tool.call(any(String.class))).thenAnswer(inv -> {
                beforeTool.run();
                String query = new ObjectMapper().readTree((String) inv.getArgument(0)).path("query").asText();
                if (noSources) return "{\"results\":[]}";
                toolQueries.add(query);
                String slug = query.contains("先行") ? "first" : query.contains("并行") ? "parallel"
                        : query.contains("后续") ? "later" : "replan";
                return new ObjectMapper().writeValueAsString(List.of(java.util.Map.of("text", java.util.Map.of(
                        "results", List.of(java.util.Map.of("url", "https://example.org/" + slug,
                                "title", "证据", "content", sourceContent))))));
            });
            SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
            when(provider.getToolCallbacks()).thenReturn(new ToolCallback[] {tool});
            when(client.prompt()).thenAnswer(inv -> prompt());
            agent = new DeepResearchAgent(builders, conversations, tasks, new ObjectMapper(), provider,
                    "test-key", "test-model", 3, contextLimit);
        }

        private ChatClient.ChatClientRequestSpec prompt() {
            ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
            AtomicReference<List<Message>> messages = new AtomicReference<>();
            when(spec.messages(any(List.class))).thenAnswer(inv -> {
                messages.set(inv.getArgument(0));
                return spec;
            });
            when(spec.messages(any(Message.class), any(Message.class))).thenAnswer(inv -> {
                messages.set(List.of(inv.getArgument(0), inv.getArgument(1)));
                return spec;
            });
            when(spec.options(any())).thenReturn(spec);
            when(spec.toolCallbacks(any(ToolCallback[].class))).thenReturn(spec);
            ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
            when(spec.call()).thenReturn(call);
            when(call.content()).thenAnswer(inv -> {
                String system = messages.get().getFirst().getText();
                String user = messages.get().getLast().getText();
                if (system.contains("需求分析专家")) return clarification;
                if (system.contains("分析点规划专家")) return "1. 行业背景\n2. 趋势";
                if (system.contains("执行计划规划专家")) {
                    int round = plans.incrementAndGet();
                    if (round == 2) secondPlanContext.set(user);
                    return round == 1 ? firstPlan : secondPlan;
                }
                if (system.contains("研究评审专家")) return critiques.incrementAndGet() == 1
                        ? "{\"passed\":false,\"feedback\":\"缺少新证据\"}"
                        : "{\"passed\":true,\"feedback\":\"\"}";
                if (system.contains("工具执行与结果整理专家")) {
                    String task = messages.get().get(1).getText();
                    task = task.substring(task.lastIndexOf("【Current Task】\n") + "【Current Task】\n".length());
                    return "核实证据 https://example.org/" +
                            (task.contains("查证先行") ? "first" : task.contains("查证并行") ? "parallel"
                                    : task.contains("查证后续") ? "later" : "replan");
                }
                return "工作上下文摘要";
            });
            when(call.chatResponse()).thenAnswer(inv -> {
                String context = messages.get().getLast().getText();
                String query = context.substring(context.indexOf("【Current Task】\n") + "【Current Task】\n".length());
                if (query.contains("查证后续")) dependentContext.set(context);
                String args = new ObjectMapper().writeValueAsString(java.util.Map.of("query", query));
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "tavily_search", args)))
                        .build())));
            });
            ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
            when(spec.stream()).thenReturn(stream);
            when(stream.content()).thenReturn(Flux.just("报告正文"));
            return spec;
        }
    }
}
