package com.sprout.agent.service;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

@Component
public class AgentTaskManager implements MessageListener, InitializingBean, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    private static final String TASK_PREFIX = "sprout:agent:task:";
    public static final String STOP_CHANNEL = "sprout:agent:stop";
    private static final Duration TASK_TTL = Duration.ofMinutes(30);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<String, TaskInfo> taskMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "agent-task-renew");
        thread.setDaemon(true);
        return thread;
    });

    public AgentTaskManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void afterPropertiesSet() {
        renewer.scheduleAtFixedRate(this::renewTasks, 5, 5, TimeUnit.MINUTES);
    }

    private String key(String conversationId) {
        return TASK_PREFIX + conversationId;
    }

    private void release(String conversationId, TaskInfo task) {
        try {
            redis.execute(RELEASE, java.util.List.of(key(conversationId)), task.lease);
        } catch (RuntimeException ex) {
            // The lease has a TTL; do not strand a completed stream when Redis is unavailable.
            log.warn("释放任务租约失败: conversationId={}", conversationId, ex);
        }
    }

    private void renewTasks() {
        for (var entry : taskMap.entrySet()) {
            try {
                Long renewed = redis.execute(RENEW, java.util.List.of(key(entry.getKey())),
                        entry.getValue().lease, Long.toString(TASK_TTL.toMillis()));
                if (!Long.valueOf(1).equals(renewed)) {
                    stopLocal(entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException ex) {
                // Do not allow an unrenewed task to outlive its distributed lease.
                log.warn("任务租约续期失败，停止本地任务: conversationId={}", entry.getKey(), ex);
                try {
                    stopLocal(entry.getKey(), entry.getValue());
                } catch (RuntimeException stopError) {
                    log.warn("停止租约失效的任务失败: conversationId={}", entry.getKey(), stopError);
                }
            }
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String request = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
        int delimiter = request.indexOf(':');
        if (delimiter < 0) return;
        String conversationId = request.substring(0, delimiter);
        TaskInfo task = taskMap.get(conversationId);
        if (task != null && task.lease.equals(request.substring(delimiter + 1))) {
            stopLocal(conversationId, task);
        }
    }

    @Override
    public void destroy() {
        renewer.shutdownNow();
        taskMap.forEach((id, task) -> {
            try {
                stopLocal(id, task);
            } catch (RuntimeException ex) {
                log.warn("关闭时停止任务失败: conversationId={}", id, ex);
            }
        });
    }

    public TaskInfo registerTask(String conversationId, Sinks.Many<ChatEvent> sink, String agentType) {
        TaskInfo candidate = new TaskInfo(sink, agentType, instanceId + ":" + UUID.randomUUID());
        TaskInfo registered = taskMap.computeIfAbsent(conversationId, id ->
                Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(id), candidate.lease, TASK_TTL))
                        ? candidate : null);
        return registered == candidate ? candidate : null;
    }


    public boolean hasRunningTask(String conversationId) {
        return taskMap.containsKey(conversationId) || Boolean.TRUE.equals(redis.hasKey(key(conversationId)));
    }

    public boolean removeTask(String conversationId, TaskInfo task) {
        if (!taskMap.remove(conversationId, task)) return false;
        release(conversationId, task);
        return true;
    }

    public void emit(String conversationId, TaskInfo task, ChatEvent event) {
        Sinks.EmitResult result = task.sink.tryEmitNext(event);
        if (result == Sinks.EmitResult.FAIL_OVERFLOW && removeTask(conversationId, task)) {
            try {
                task.cancel();
            } finally {
                task.sink.tryEmitError(new IllegalStateException("客户端消费过慢，已停止生成"));
            }
        }
    }

    public boolean stopTask(String conversationId) {
        TaskInfo local = taskMap.get(conversationId);
        if (local != null) return stopLocal(conversationId, local);
        String holder = redis.opsForValue().get(key(conversationId));
        if (holder == null || holder.startsWith(instanceId + ":")) return false;
        return redis.convertAndSend(STOP_CHANNEL, conversationId + ":" + holder) > 0;
    }

    private boolean stopLocal(String conversationId, TaskInfo task) {
        if (!taskMap.remove(conversationId, task)) return false;
        try {
            release(conversationId, task);
        } finally {
            task.stop();
        }
        return true;
    }


    public static final class TaskInfo {
        private final String lease;
        private final Sinks.Many<ChatEvent> sink;
        private final long createTime = Instant.now().toEpochMilli();
        private final String agentType;
        private Disposable disposable;
        private boolean stopped;
        private Runnable onStop = () -> {};

        private TaskInfo(Sinks.Many<ChatEvent> sink, String agentType, String lease) {
            this.sink = sink;
            this.agentType = agentType;
            this.lease = lease;
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
                sink.tryEmitNext(new ChatEvent("stopped", "已停止生成"));
                sink.tryEmitComplete();
            }
        }

        public long getCreateTime() { return createTime; }
        public String getAgentType() { return agentType; }
    }
}
