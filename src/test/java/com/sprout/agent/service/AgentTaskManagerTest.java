package com.sprout.agent.service;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.Disposables;
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
}
