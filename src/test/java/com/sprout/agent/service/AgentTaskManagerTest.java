package com.sprout.agent.service;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Sinks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskManagerTest {
    private final Map<String, String> leases = new ConcurrentHashMap<>();
    private final List<AgentTaskManager> instances = new ArrayList<>();
    private StringRedisTemplate redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(call -> leases.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(values.get(anyString())).thenAnswer(call -> leases.get(call.getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(call -> leases.containsKey(call.getArgument(0)));
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString())).thenAnswer(call -> {
                    String key = ((List<String>) call.getArgument(1)).getFirst();
                    return leases.remove(key, call.getArgument(2)) ? 1L : 0L;
                });
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), anyString())).thenAnswer(call -> {
                    String key = ((List<String>) call.getArgument(1)).getFirst();
                    return leases.getOrDefault(key, "").equals(call.getArgument(2)) ? 1L : 0L;
                });
        when(redis.convertAndSend(eq(AgentTaskManager.STOP_CHANNEL), anyString())).thenAnswer(call -> {
            Message message = mock(Message.class);
            when(message.getBody()).thenReturn(((String) call.getArgument(1)).getBytes(StandardCharsets.UTF_8));
            for (AgentTaskManager instance : instances) instance.onMessage(message, null);
            return (long) instances.size();
        });
    }

    private AgentTaskManager instance() {
        AgentTaskManager manager = new AgentTaskManager(redis);
        instances.add(manager);
        return manager;
    }

    @AfterEach
    void tearDown() {
        instances.forEach(AgentTaskManager::destroy);
    }


    @Test
    void duplicateTaskRejectedAndStoppingDisposesStreamAndAllowsNextTask() {
        AgentTaskManager manager = instance();
        String id = "0123456789abcdef0123456789abcdef";
        Sinks.Many<ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        List<ChatEvent> events = new ArrayList<>();
        sink.asFlux().subscribe(events::add);
        AgentTaskManager.TaskInfo first = manager.registerTask(id, sink, "web");
        assertNotNull(first);
        first.onStop(() -> events.add(new ChatEvent("saved", "partial answer")));
        assertNull(manager.registerTask(id, Sinks.many().unicast().onBackpressureBuffer(), "file"));
        assertTrue(manager.stopTask(id));
        assertEquals(List.of("saved", "stopped"), events.stream().map(ChatEvent::name).toList());
        assertFalse(manager.hasRunningTask(id));
        Disposable late = Disposables.single();
        first.setDisposable(late);
        assertTrue(late.isDisposed());
        AgentTaskManager.TaskInfo next = manager.registerTask(id,
                Sinks.many().unicast().onBackpressureBuffer(), "file");
        assertNotNull(next);
        manager.removeTask(id, first);
        assertTrue(manager.hasRunningTask(id));
    }
    @Test
    void slowConsumerStopsProducerInsteadOfGrowingQueueOrDroppingAnswer() {
        AgentTaskManager manager = instance();
        String id = "0123456789abcdef0123456789abcdef";
        Sinks.Many<ChatEvent> sink = Sinks.many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(2));
        List<String> received = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        BaseSubscriber<ChatEvent> subscriber = new BaseSubscriber<>() {
            @Override protected void hookOnSubscribe(Subscription subscription) {}
            @Override protected void hookOnNext(ChatEvent event) { received.add(event.data()); }
            @Override protected void hookOnError(Throwable error) { errors.add(error); }
        };
        sink.asFlux().subscribe(subscriber);
        AgentTaskManager.TaskInfo task = manager.registerTask(id, sink, "react");
        Disposable execution = Disposables.single();
        task.setDisposable(execution);
        AtomicBoolean saved = new AtomicBoolean();
        task.onStop(() -> saved.set(true));
        manager.emit(id, task, new ChatEvent("delta", "a"));
        manager.emit(id, task, new ChatEvent("delta", "b"));
        manager.emit(id, task, new ChatEvent("delta", "c"));

        assertTrue(execution.isDisposed());
        assertTrue(saved.get());
        assertFalse(manager.hasRunningTask(id));
        subscriber.request(2);
        assertEquals(List.of("a", "b"), received);
        assertEquals("客户端消费过慢，已停止生成", errors.getFirst().getMessage());
    }

    @Test
    void otherInstanceRejectsDuplicateAndStopsOwnerWithoutStoppingNewLease() {
        AgentTaskManager owner = instance();
        AgentTaskManager peer = instance();
        String id = "0123456789abcdef0123456789abcdef";
        List<ChatEvent> events = new ArrayList<>();
        Sinks.Many<ChatEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        sink.asFlux().subscribe(events::add);
        AgentTaskManager.TaskInfo first = owner.registerTask(id, sink, "research");
        Disposable work = Disposables.single();
        first.setDisposable(work);
        assertTrue(peer.hasRunningTask(id));
        assertNull(peer.registerTask(id, Sinks.many().unicast().onBackpressureBuffer(), "ppt"));
        String previousLease = leases.get("sprout:agent:task:" + id);
        assertTrue(peer.stopTask(id));
        assertTrue(work.isDisposed());
        assertEquals(List.of("stopped"), events.stream().map(ChatEvent::name).toList());
        assertFalse(peer.hasRunningTask(id));

        AgentTaskManager.TaskInfo next = peer.registerTask(id,
                Sinks.many().unicast().onBackpressureBuffer(), "ppt");
        assertNotNull(next);
        Message stale = mock(Message.class);
        when(stale.getBody()).thenReturn((id + ":" + previousLease).getBytes(StandardCharsets.UTF_8));
        peer.onMessage(stale, null);
        assertFalse(owner.removeTask(id, first));
        assertTrue(peer.hasRunningTask(id));
        assertTrue(peer.removeTask(id, next));
    }

    @Test
    void expiredLeaseStopsOldTaskWithoutDeletingReplacement() {
        AgentTaskManager owner = instance();
        AgentTaskManager peer = instance();
        String id = "abcdef0123456789abcdef0123456789";
        AgentTaskManager.TaskInfo task = owner.registerTask(id,
                Sinks.many().unicast().onBackpressureBuffer(), "research");
        Disposable work = Disposables.single();
        task.setDisposable(work);
        leases.put("sprout:agent:task:" + id, "another-instance:new-lease");

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(owner, "renewTasks");

        assertTrue(work.isDisposed());
        assertTrue(peer.hasRunningTask(id));
        assertEquals("another-instance:new-lease", leases.get("sprout:agent:task:" + id));
    }

}
