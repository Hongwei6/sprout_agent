package com.sprout.agent.agents.ppt;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Executes model-requested searches explicitly, retaining tool responses across rounds. */
final class SimpleReactAgent {
    private final ChatClient client;
    private final ToolCallback[] tools;

    SimpleReactAgent(ChatClient client, ToolCallback[] tools) {
        this.client = client;
        this.tools = tools;
    }

    Flux<ChatEvent> stream(String prompt) {
        if (tools.length == 0) return Flux.error(new IllegalStateException("没有可用的 Tavily 搜索工具"));
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(prompt));
        return round(messages);
    }

    private Flux<ChatEvent> round(List<Message> messages) {
        var state = new RoundState();
        return client.prompt().messages(messages)
                .options(OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build())
                .toolCallbacks(tools).stream().chatResponse()
                .concatMap(chunk -> {
                    if (chunk.getResult() == null || chunk.getResult().getOutput() == null) return Flux.<ChatEvent>empty();
                    AssistantMessage output = chunk.getResult().getOutput();
                    if (output.hasToolCalls()) {
                        for (AssistantMessage.ToolCall call : output.getToolCalls()) merge(state.calls, call);
                    }
                    if (output.getText() != null) state.text.append(output.getText());
                    return Flux.<ChatEvent>empty();
                })
                .concatWith(Flux.defer(() -> {
                    if (state.calls.isEmpty()) {
                        return Flux.error(new IllegalStateException("模型未调用 Tavily 搜索工具"));
                    }
                    messages.add(AssistantMessage.builder().content(state.text.toString()).toolCalls(state.calls).build());
                    return Flux.fromIterable(state.calls).concatMap(call -> Flux.concat(
                            Mono.just(new ChatEvent("thinking", "正在搜索：" + call.name())),
                            Mono.fromCallable(() -> {
                                ToolCallback callback = findTool(call.name());
                                if (callback == null) throw new IllegalStateException("搜索工具不存在：" + call.name());
                                String result = callback.call(call.arguments());
                                return new ToolResponseMessage.ToolResponse(call.id(), call.name(), result);
                            }).subscribeOn(Schedulers.boundedElastic()).doOnNext(response -> state.responses.add(response))
                                    .then(Mono.<ChatEvent>empty())))
                            .concatWith(Flux.defer(() -> {
                                messages.add(ToolResponseMessage.builder().responses(state.responses).build());
                                messages.add(new UserMessage("搜索已完成。只根据上述工具返回的资料，用简体中文整理背景信息、关键数据和案例；"
                                        + "不再调用搜索工具，不编造未检索到的信息。"));
                                return client.prompt().messages(messages)
                                        .options(OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build())
                                        .stream().content().filter(text -> !text.isEmpty())
                                        .map(text -> new ChatEvent("delta", text));
                            }));
                }));
    }

    private ToolCallback findTool(String name) {
        for (ToolCallback tool : tools) {
            if (tool.getToolDefinition().name().equals(name)) return tool;
        }
        return null;
    }

    private static void merge(List<AssistantMessage.ToolCall> calls, AssistantMessage.ToolCall incoming) {
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall previous = calls.get(i);
            if (incoming.id() != null && !incoming.id().isBlank() && incoming.id().equals(previous.id())) {
                calls.set(i, new AssistantMessage.ToolCall(previous.id(), "function",
                        previous.name(), Objects.toString(previous.arguments(), "")
                                + Objects.toString(incoming.arguments(), "")));
                return;
            }
        }
        calls.add(incoming);
    }

    private static final class RoundState {
        final List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        final List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
    }
}
