package com.sprout.agent.agents.ppt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

class PptBuilderAgentTest {
    @Test
    void persistsStageCheckpointsAndCompletesRender() {
        var conversations = mock(ConversationService.class);
        var instances = mock(PptInstService.class);
        var intents = mock(PptIntentRecognizer.class);
        var builders = mock(ObjectProvider.class);
        var builder = mock(ChatClient.Builder.class);
        var tools = mock(SyncMcpToolCallbackProvider.class);
        var templates = mock(PptTemplateCatalog.class);
        var inst = new AiPptInst();
        inst.setId(42L);
        inst.setStatus(PptInstStatus.INIT);
        String id = "a".repeat(32);
        when(conversations.createConversation(null, "做一份关于绿色能源的 PPT"))
                .thenReturn(id);
        when(intents.recognize(id, "做一份关于绿色能源的 PPT"))
                .thenReturn(new PptIntentRecognizer.PptIntentResult(
                        PptIntentRecognizer.PptIntent.CREATE_PPT, "新建"));
        when(instances.createInst(id, "做一份关于绿色能源的 PPT")).thenReturn(inst);
        when(instances.find(42L)).thenReturn(inst);
        when(builders.getObject()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        when(tools.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        var phases = List.of(PptInstStatus.REQUIREMENT, PptInstStatus.SEARCH,
                PptInstStatus.TEMPLATE, PptInstStatus.OUTLINE, PptInstStatus.SCHEMA, PptInstStatus.RENDER);
        var index = new AtomicInteger();
        List<PptStateStrategy> strategies = phases.stream().<PptStateStrategy>map(phase -> new PptStateStrategy() {
            @Override public PptInstStatus getTargetStatus() { return phase; }
            @Override public Flux<ChatEvent> execute(AiPptInst current, String query, PptStateStrategyContext context) {
                assertEquals(phase, current.getStatus());
                assertEquals(phase, phases.get(index.getAndIncrement()));
                return Flux.just(new ChatEvent("delta", phase.name())).doOnComplete(() ->
                        current.setStatus(phases.indexOf(phase) == phases.size() - 1
                                ? PptInstStatus.SUCCESS : phases.get(phases.indexOf(phase) + 1)));
            }
        }).toList();
        when(instances.getLatestInst(id)).thenReturn(inst);
        var agent = new PptBuilderAgent(conversations, instances, intents,
                new PptStateStrategyFactory(strategies), templates, new AgentTaskManager(), builders, tools,
                "test-key", "test-model");
        // The INIT transition is committed before the first stage is read.
        org.mockito.Mockito.doAnswer(invocation -> {
            inst.setStatus(invocation.getArgument(1));
            return null;
        }).when(instances).updateStatus(eq(42L), eq(PptInstStatus.REQUIREMENT));
        List<ChatEvent> events = agent.stream(null, "做一份关于绿色能源的 PPT")
                .collectList().block(Duration.ofSeconds(5));
        assertEquals(6, index.get());
        assertEquals(PptInstStatus.SUCCESS, inst.getStatus());
        assertEquals("conversation", events.getFirst().name());
        assertEquals("done", events.getLast().name());
        verify(conversations).saveExchange(eq(id), eq("做一份关于绿色能源的 PPT"), any());
        assertFalse(events.stream().anyMatch(event -> event.name().equals("error")));
    }

    @Test
    void onlyExistingFinishedPresentationCanBeModified() {
        var instances = mock(PptInstService.class);
        var recognizer = new PptIntentRecognizer(instances);
        String id = "b".repeat(32);
        assertEquals(PptIntentRecognizer.PptIntent.CREATE_PPT,
                recognizer.recognize(id, "生成一份关于春天的 PPT").intent());
        AiPptInst pending = new AiPptInst();
        pending.setStatus(PptInstStatus.FAILED);
        when(instances.getLatestInst(id)).thenReturn(pending);
        assertEquals(PptIntentRecognizer.PptIntent.RESUME_PPT,
                recognizer.recognize(id, "继续生成").intent());
        assertEquals(PptIntentRecognizer.PptIntent.MODIFY_PPT,
                recognizer.recognize(id, "修改之前的 PPT 封面标题").intent());
        assertTrue(recognizer.recognize(id, "修改之前的 PPT 封面标题").reason().contains("没有已完成"));
        pending.setFailedStage(PptInstStatus.REQUIREMENT);
        assertEquals(PptIntentRecognizer.PptIntent.RESUME_PPT,
                recognizer.recognize(id, "修改主题为风电").intent());
        pending.setFailedStage(null);
        pending.setStatus(PptInstStatus.SCHEMA);
        assertEquals(PptIntentRecognizer.PptIntent.CREATE_PPT,
                recognizer.recognize(id, "生成一份关于风电的新 PPT").intent());
        assertEquals(PptIntentRecognizer.PptIntent.RESUME_PPT,
                recognizer.recognize(id, "继续生成上一份 PPT").intent());
        pending.setStatus(PptInstStatus.SUCCESS);
        assertEquals(PptIntentRecognizer.PptIntent.RESUME_PPT,
                recognizer.recognize(id, "继续").intent());
        assertEquals(PptIntentRecognizer.PptIntent.CREATE_PPT,
                recognizer.recognize(id, "新建一份 PPT").intent());
    }
    @Test
    void resumesFailedStageWithoutRepeatingEarlierWorkAndPersistsFailure() {
        var conversations = mock(ConversationService.class);
        var instances = mock(PptInstService.class);
        var builders = mock(ObjectProvider.class);
        var builder = mock(ChatClient.Builder.class);
        var tools = mock(SyncMcpToolCallbackProvider.class);
        String id = "c".repeat(32);
        AiPptInst inst = new AiPptInst();
        inst.setId(50L);
        inst.setConversationId(id);
        inst.setStatus(PptInstStatus.OUTLINE);
        when(conversations.createConversation(eq(id), any())).thenReturn(id);
        when(instances.getLatestInst(id)).thenReturn(inst);
        when(instances.find(50L)).thenReturn(inst);
        when(builders.getObject()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        when(tools.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        var attempts = new AtomicInteger();
        PptStateStrategy outline = new PptStateStrategy() {
            @Override public PptInstStatus getTargetStatus() { return PptInstStatus.OUTLINE; }
            @Override public Flux<ChatEvent> execute(AiPptInst current, String query, PptStateStrategyContext context) {
                return Flux.just(new ChatEvent("thinking", "生成大纲")).concatWith(Flux.defer(() -> {
                    if (attempts.incrementAndGet() == 1) return Flux.error(new IllegalStateException("模型异常"));
                    inst.setStatus(PptInstStatus.SCHEMA);
                    return Flux.just(new ChatEvent("delta", "大纲完成"));
                }));
            }
        };
        PptStateStrategy schema = new PptStateStrategy() {
            @Override public PptInstStatus getTargetStatus() { return PptInstStatus.SCHEMA; }
            @Override public Flux<ChatEvent> execute(AiPptInst current, String query, PptStateStrategyContext context) {
                inst.setStatus(PptInstStatus.SUCCESS);
                return Flux.just(new ChatEvent("delta", "生成完成"));
            }
        };
        org.mockito.Mockito.doAnswer(inv -> {
            inst.setFailedStage(inv.getArgument(2));
            inst.setStatus(PptInstStatus.FAILED);
            inst.setError(inv.getArgument(1));
            return null;
        }).when(instances).updateError(eq(50L), any(), eq(PptInstStatus.OUTLINE));
        when(instances.resume(50L)).thenAnswer(inv -> {
            inst.setStatus(inst.getFailedStage());
            inst.setFailedStage(null);
            return inst;
        });
        var agent = new PptBuilderAgent(conversations, instances, new PptIntentRecognizer(instances),
                new PptStateStrategyFactory(List.of(outline, schema, new FailedStrategy())),
                mock(PptTemplateCatalog.class), new AgentTaskManager(), builders, tools, "key", "model");
        List<ChatEvent> failed = agent.stream(id, "继续").collectList().block(Duration.ofSeconds(5));
        assertEquals(PptInstStatus.FAILED, inst.getStatus());
        assertEquals(PptInstStatus.OUTLINE, inst.getFailedStage());
        assertEquals("done", failed.getLast().name());
        assertTrue(failed.stream().anyMatch(event -> event.data().contains("已保存此前的进度")));
        ArgumentCaptor<ConversationService.TurnResult> saved = ArgumentCaptor.forClass(ConversationService.TurnResult.class);
        verify(conversations).saveExchange(eq(id), eq("继续"), saved.capture());
        assertTrue(saved.getValue().thinking().contains("生成大纲"));
        assertTrue(saved.getValue().answer().contains("已保存此前的进度"));

        List<ChatEvent> resumed = agent.stream(id, "重试").collectList().block(Duration.ofSeconds(5));
        assertEquals(2, attempts.get());
        assertEquals(PptInstStatus.SUCCESS, inst.getStatus());
        assertEquals("done", resumed.getLast().name());
        assertTrue(resumed.stream().anyMatch(event -> event.data().contains("生成完成")));
    }

    @Test
    void stopKeepsCheckpointAndNextRequestResumesIt() throws Exception {
        var conversations = mock(ConversationService.class);
        var instances = mock(PptInstService.class);
        var builders = mock(ObjectProvider.class);
        var builder = mock(ChatClient.Builder.class);
        var tools = mock(SyncMcpToolCallbackProvider.class);
        var tasks = new AgentTaskManager();
        String id = "d".repeat(32);
        AiPptInst inst = new AiPptInst();
        inst.setId(77L);
        inst.setConversationId(id);
        inst.setStatus(PptInstStatus.SCHEMA);
        when(conversations.createConversation(eq(id), any())).thenReturn(id);
        when(instances.getLatestInst(id)).thenReturn(inst);
        when(instances.find(77L)).thenReturn(inst);
        when(builders.getObject()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        when(tools.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        var started = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        PptStateStrategy schema = new PptStateStrategy() {
            @Override public PptInstStatus getTargetStatus() { return PptInstStatus.SCHEMA; }
            @Override public Flux<ChatEvent> execute(AiPptInst current, String query, PptStateStrategyContext context) {
                if (attempts.incrementAndGet() == 1) {
                    return Flux.just(new ChatEvent("thinking", "正在配图"))
                            .concatWith(Flux.defer(() -> {
                                started.countDown();
                                return Flux.<ChatEvent>never();
                            }));
                }
                inst.setStatus(PptInstStatus.SUCCESS);
                return Flux.just(new ChatEvent("delta", "已完成"));
            }
        };
        var agent = new PptBuilderAgent(conversations, instances, new PptIntentRecognizer(instances),
                new PptStateStrategyFactory(List.of(schema, new FailedStrategy())),
                mock(PptTemplateCatalog.class), tasks, builders, tools, "key", "model");
        var events = new java.util.concurrent.CopyOnWriteArrayList<ChatEvent>();
        agent.stream(id, "继续").subscribe(events::add);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(tasks.stopTask(id));
        assertEquals(PptInstStatus.SCHEMA, inst.getStatus());
        assertTrue(events.stream().anyMatch(event -> event.name().equals("stopped")));

        List<ChatEvent> resumed = agent.stream(id, "继续").collectList().block(Duration.ofSeconds(5));
        assertEquals(2, attempts.get());
        assertEquals(PptInstStatus.SUCCESS, inst.getStatus());
        assertEquals("done", resumed.getLast().name());
    }

    @Test
    void templateCatalogUsesActualPresentation() throws Exception {
        var catalog = new PptTemplateCatalog();
        var template = catalog.require("ai");
        assertEquals(5, template.slideCount());
        assertTrue(template.structure().contains("COVER"));
        assertTrue(template.structure().contains("END"));
    }
}
