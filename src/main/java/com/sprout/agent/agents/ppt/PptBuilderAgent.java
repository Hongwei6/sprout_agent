package com.sprout.agent.agents.ppt;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@Service
public class PptBuilderAgent {
    private final ConversationService conversations;
    private final PptInstService instances;
    private final PptIntentRecognizer intents;
    private final PptStateStrategyFactory strategies;
    private final PptTemplateCatalog templates;
    private final AgentTaskManager tasks;
    private final ObjectProvider<ChatClient.Builder> chatBuilders;
    private final SyncMcpToolCallbackProvider toolProvider;
    private final String apiKey;
    private final String model;

    public PptBuilderAgent(ConversationService conversations, PptInstService instances,
            PptIntentRecognizer intents, PptStateStrategyFactory strategies,
            PptTemplateCatalog templates, AgentTaskManager tasks,
            ObjectProvider<ChatClient.Builder> chatBuilders, SyncMcpToolCallbackProvider toolProvider,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:}") String model) {
        this.conversations = conversations;
        this.instances = instances;
        this.intents = intents;
        this.strategies = strategies;
        this.templates = templates;
        this.tasks = tasks;
        this.chatBuilders = chatBuilders;
        this.toolProvider = toolProvider;
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isAvailable() {
        return !apiKey.isBlank() && !apiKey.equals("local-placeholder")
                && !model.isBlank() && chatBuilders.getIfAvailable() != null;
    }

    public Flux<ChatEvent> stream(String requestedConversationId, String query) {
        return Flux.defer(() -> {
            String conversationId = conversations.createConversation(requestedConversationId, query);
            Sinks.Many<ChatEvent> sink = Sinks.many().unicast()
                    .onBackpressureBuffer(new ArrayBlockingQueue<>(256));
            AgentTaskManager.TaskInfo task = tasks.registerTask(conversationId, sink, "ppt");
            if (task == null) return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
            return sink.asFlux().doOnSubscribe(subscription -> {
                AtomicBoolean stopped = new AtomicBoolean();
                task.onStop(() -> stopped.set(true));
                Disposable execution = Flux.defer(() -> {
                    var intent = intents.recognize(conversationId, query);
                    if (intent.intent() == PptIntentRecognizer.PptIntent.MODIFY_PPT) {
                        return Flux.<ChatEvent>error(new IllegalStateException("当前尚无可修改的 PPT 文件，请新建演示文稿"));
                    }
                    AiPptInst inst = intent.intent() == PptIntentRecognizer.PptIntent.CREATE_PPT
                            ? instances.createInst(conversationId, query) : instances.getLatestInst(conversationId);
                    if (inst == null) return Flux.error(new IllegalStateException("没有可恢复的 PPT 任务"));
                    if (intent.intent() == PptIntentRecognizer.PptIntent.RESUME_PPT
                            && inst.getStatus() == PptInstStatus.FAILED) inst = instances.resume(inst.getId());
                    if (inst.getStatus() == PptInstStatus.INIT) {
                        instances.updateStatus(inst.getId(), PptInstStatus.REQUIREMENT);
                    }
                    if (inst.getStatus() == PptInstStatus.SUCCESS) {
                        return Flux.just(new ChatEvent("conversation", conversationId),
                                new ChatEvent("delta", "PPT 已生成完成，可通过 /api/ppt/conversations/"
                                        + conversationId + "/download 下载；如需新版本，请明确提出新建需求。"),
                                new ChatEvent("done", ""));
                    }
                    ChatClient client = chatBuilders.getObject().build();
                    var context = new PptStateStrategyContext(instances, client,
                            toolProvider.getToolCallbacks(), templates, () -> !stopped.get());
                    long id = inst.getId();
                    StringBuilder answer = new StringBuilder();
                    StringBuilder thinking = new StringBuilder();
                    long started = System.nanoTime();
                    return Flux.just(new ChatEvent("conversation", conversationId))
                            .concatWith(runNext(id, query, context))
                            .onErrorResume(error -> {
                                if (stopped.get()) return Flux.empty();
                                AiPptInst current = instances.find(id);
                                if (current == null) return Flux.error(error);
                                if (current.getStatus() != PptInstStatus.FAILED) {
                                    instances.updateError(id, error.getMessage(), current.getStatus());
                                    current = instances.find(id);
                                }
                                return strategies.require(PptInstStatus.FAILED).execute(current, query, context);
                            })
                            .doOnNext(event -> {
                                if ("delta".equals(event.name())) answer.append(event.data());
                                if ("thinking".equals(event.name())) thinking.append(event.data()).append('\n');
                                if ("done".equals(event.name()) && !answer.isEmpty()) {
                                    conversations.saveExchange(conversationId, query, new ConversationService.TurnResult(
                                            answer.toString(), thinking.toString(), "[]", "[]", null, null,
                                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                                    System.nanoTime() - started)));
                                }
                            });
                }).subscribeOn(Schedulers.boundedElastic()).subscribe(
                        event -> tasks.emit(conversationId, task, event),
                        error -> {
                            if (tasks.removeTask(conversationId, task)) {
                                sink.tryEmitNext(new ChatEvent("error", "PPT 任务启动失败：" + error.getMessage()));
                                sink.tryEmitComplete();
                            }
                        },
                        () -> { if (tasks.removeTask(conversationId, task)) sink.tryEmitComplete(); });
                task.setDisposable(execution);
            }).doOnCancel(() -> {
                tasks.removeTask(conversationId, task);
                task.cancel();
            });
        });
    }

    private Flux<ChatEvent> runNext(long id, String query, PptStateStrategyContext context) {
        return Flux.defer(() -> {
            if (!context.active().getAsBoolean()) return Flux.empty();
            AiPptInst inst = instances.find(id);
            PptInstStatus status = inst.getStatus();
            if (status == PptInstStatus.SUCCESS) {
                return Flux.just(new ChatEvent("done", ""));
            }
            if (status == PptInstStatus.FAILED) {
                return strategies.require(status).execute(inst, query, context);
            }
            PptStateStrategy strategy = strategies.require(status);
            return strategy.execute(inst, query, context).concatWith(Flux.defer(() -> {
                AiPptInst updated = instances.find(id);
                return updated.getStatus() == status
                        ? Flux.just(new ChatEvent("done", "")) : runNext(id, query, context);
            }));
        });
    }
}
