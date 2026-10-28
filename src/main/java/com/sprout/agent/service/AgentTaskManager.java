package com.sprout.agent.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

@Component
public class AgentTaskManager {

    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    public TaskInfo registerTask(String conversationId, Sinks.Many<ReactAgentService.ChatEvent> sink, String agentType) {
        TaskInfo task = new TaskInfo(sink, agentType);
        return taskMap.putIfAbsent(conversationId, task) == null ? task : null;
    }

    public boolean hasRunningTask(String conversationId) {
        return taskMap.containsKey(conversationId);
    }

    public boolean removeTask(String conversationId, TaskInfo task) {
        return taskMap.remove(conversationId, task);
    }

    public boolean stopTask(String conversationId) {
        TaskInfo task = taskMap.get(conversationId);
        if (task == null) return false;
        try {
            task.stop();
        } finally {
            taskMap.remove(conversationId, task);
        }
        return true;
    }

    public static final class TaskInfo {
        private final Sinks.Many<ReactAgentService.ChatEvent> sink;
        private final long createTime = Instant.now().toEpochMilli();
        private final String agentType;
        private Disposable disposable;
        private boolean stopped;
        private Runnable onStop = () -> {};

        private TaskInfo(Sinks.Many<ReactAgentService.ChatEvent> sink, String agentType) {
            this.sink = sink;
            this.agentType = agentType;
        }

        public synchronized void setDisposable(Disposable disposable) {
            this.disposable = disposable;
            if (stopped) disposable.dispose();
        }
        public synchronized void onStop(Runnable action) {
            this.onStop = action;
        }

        public synchronized void cancel() {
            if (stopped) return;
            stopped = true;
            if (disposable != null) disposable.dispose();
            onStop.run();
        }

        private synchronized void stop() {
            try {
                cancel();
            } finally {
                sink.tryEmitNext(new ReactAgentService.ChatEvent("stopped", "已停止生成"));
                sink.tryEmitComplete();
            }
        }

        public long getCreateTime() { return createTime; }
        public String getAgentType() { return agentType; }
    }
}
