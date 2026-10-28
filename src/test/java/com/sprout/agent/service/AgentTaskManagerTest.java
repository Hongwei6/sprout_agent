package com.sprout.agent.service;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.BaseSubscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskManagerTest {

    @Test
    void duplicateTaskRejectedAndStoppingDisposesStreamAndAllowsNextTask() {
        AgentTaskManager manager = new AgentTaskManager();
        String id = "0123456789abcdef0123456789abcdef";
        Sinks.Many<ReactAgentService.ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        List<ReactAgentService.ChatEvent> events = new ArrayList<>();
        sink.asFlux().subscribe(events::add);
        AgentTaskManager.TaskInfo first = manager.registerTask(id, sink, "react");
        assertNotNull(first);
        first.onStop(() -> events.add(new ReactAgentService.ChatEvent("saved", "partial answer")));
        assertNull(manager.registerTask(id, Sinks.many().unicast().onBackpressureBuffer(), "react"));
        assertTrue(manager.stopTask(id));
        assertEquals(List.of("saved", "stopped"), events.stream().map(ReactAgentService.ChatEvent::name).toList());
        assertFalse(manager.hasRunningTask(id));
        Disposable late = Disposables.single();
        first.setDisposable(late);
        assertTrue(late.isDisposed());
        AgentTaskManager.TaskInfo next = manager.registerTask(id,
                Sinks.many().unicast().onBackpressureBuffer(), "react");
        assertNotNull(next);
        manager.removeTask(id, first);
        assertTrue(manager.hasRunningTask(id));
    }
    @Test
    void slowConsumerStopsProducerInsteadOfGrowingQueueOrDroppingAnswer() {
        AgentTaskManager manager = new AgentTaskManager();
        String id = "0123456789abcdef0123456789abcdef";
        Sinks.Many<ReactAgentService.ChatEvent> sink = Sinks.many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(2));
        List<String> received = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        BaseSubscriber<ReactAgentService.ChatEvent> subscriber = new BaseSubscriber<>() {
            @Override protected void hookOnSubscribe(Subscription subscription) {}
            @Override protected void hookOnNext(ReactAgentService.ChatEvent event) { received.add(event.data()); }
            @Override protected void hookOnError(Throwable error) { errors.add(error); }
        };
        sink.asFlux().subscribe(subscriber);
        AgentTaskManager.TaskInfo task = manager.registerTask(id, sink, "react");
        Disposable execution = Disposables.single();
        task.setDisposable(execution);
        AtomicBoolean saved = new AtomicBoolean();
        task.onStop(() -> saved.set(true));
        manager.emit(id, task, new ReactAgentService.ChatEvent("delta", "a"));
        manager.emit(id, task, new ReactAgentService.ChatEvent("delta", "b"));
        manager.emit(id, task, new ReactAgentService.ChatEvent("delta", "c"));

        assertTrue(execution.isDisposed());
        assertTrue(saved.get());
        assertFalse(manager.hasRunningTask(id));
        subscriber.request(2);
        assertEquals(List.of("a", "b"), received);
        assertEquals("客户端消费过慢，已停止生成", errors.getFirst().getMessage());
    }

}
