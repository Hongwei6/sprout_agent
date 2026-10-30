package com.sprout.agent.agents.ppt;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import com.sprout.agent.prompts.PptBuilderPrompts;
import java.util.function.BooleanSupplier;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

interface PptStateStrategy {
    PptInstStatus getTargetStatus();
    Flux<ChatEvent> execute(AiPptInst inst, String query, PptStateStrategyContext context);
}

record PptStateStrategyContext(PptInstService instances, ChatClient client,
                               ToolCallback[] tools, PptTemplateCatalog templates, BooleanSupplier active) {
}

@Component
class RequirementStrategy implements PptStateStrategy {
    @Override public PptInstStatus getTargetStatus() { return PptInstStatus.REQUIREMENT; }

    @Override public Flux<ChatEvent> execute(AiPptInst inst, String query, PptStateStrategyContext context) {
        String previous = inst.getRequirement();
        String request = previous == null || previous.isBlank() ? query
                : "已有需求与待澄清内容：\n" + previous + "\n用户补充：\n" + query;
        StringBuilder answer = new StringBuilder();
        return Flux.concat(Flux.just(new ChatEvent("thinking", "正在澄清 PPT 需求")),
                context.client().prompt().system(PptBuilderPrompts.REQUIREMENT_PROMPT)
                        .user(request).stream().content().filter(chunk -> !chunk.isEmpty())
                        .doOnNext(answer::append).map(chunk -> new ChatEvent("delta", chunk)),
                Flux.defer(() -> {
                    String result = answer.toString();
                    if (result.isBlank()) return Flux.error(new IllegalStateException("需求澄清结果为空"));
                    if (!result.contains("【开始生成PPT】")) {
                        context.instances().updateRequirement(inst.getId(), result, PptInstStatus.REQUIREMENT);
                        context.instances().updateError(inst.getId(), "需求信息不足", PptInstStatus.REQUIREMENT);
                        return Flux.empty();
                    }
                    context.instances().updateRequirement(inst.getId(), result, PptInstStatus.SEARCH);
                    return Flux.just(new ChatEvent("thinking", "需求已确认，开始收集资料"));
                }));
    }
}

@Component
class SearchStrategy implements PptStateStrategy {
    @Override public PptInstStatus getTargetStatus() { return PptInstStatus.SEARCH; }

    @Override public Flux<ChatEvent> execute(AiPptInst inst, String query, PptStateStrategyContext context) {
        StringBuilder result = new StringBuilder();
        return Flux.concat(Flux.just(new ChatEvent("thinking", "正在使用 Tavily 收集背景资料")),
                new SimpleReactAgent(context.client(), context.tools())
                        .stream(PptBuilderPrompts.getSearchInfoPrompt(inst.getRequirement()))
                        .doOnNext(event -> { if ("delta".equals(event.name())) result.append(event.data()); }),
                Flux.defer(() -> {
                    if (result.isEmpty()) return Flux.error(new IllegalStateException("未取得搜索摘要"));
                    context.instances().updateSearchInfo(inst.getId(), result.toString(), PptInstStatus.TEMPLATE);
                    return Flux.just(new ChatEvent("thinking", "资料已收集，开始选择模板"));
                }));
    }
}

@Component
class TemplateStrategy implements PptStateStrategy {
    @Override public PptInstStatus getTargetStatus() { return PptInstStatus.TEMPLATE; }

    @Override public Flux<ChatEvent> execute(AiPptInst inst, String query, PptStateStrategyContext context) {
        return Flux.defer(() -> {
            List<PptTemplateCatalog.Template> templates = context.templates().available();
            if (templates.isEmpty()) return Flux.error(new IllegalStateException("没有可用的 PPT 模板"));
            return select(inst, context, templates.getFirst());
        });
    }

    private Flux<ChatEvent> select(AiPptInst inst, PptStateStrategyContext context, PptTemplateCatalog.Template template) {
        context.instances().updateTemplate(inst.getId(), template.code(), PptInstStatus.OUTLINE);
        return Flux.just(new ChatEvent("thinking", "已选择模板「" + template.name() + "」，开始生成大纲"));
    }
}

@Component
class OutlineStrategy implements PptStateStrategy {
    @Override public PptInstStatus getTargetStatus() { return PptInstStatus.OUTLINE; }

    @Override public Flux<ChatEvent> execute(AiPptInst inst, String query, PptStateStrategyContext context) {
        PptTemplateCatalog.Template template = context.templates().require(inst.getTemplateCode());
        String prompt = PptBuilderPrompts.getOutlinePrompt(inst.getRequirement(), template.structure(),
                template.name(), inst.getSearchInfo());
        StringBuilder outline = new StringBuilder();
        return Flux.concat(Flux.just(new ChatEvent("thinking", "正在编排 PPT 大纲")),
                context.client().prompt().user(prompt).stream().content().filter(chunk -> !chunk.isEmpty())
                        .doOnNext(outline::append).map(chunk -> new ChatEvent("delta", chunk)),
                Flux.defer(() -> {
                    if (outline.isEmpty()) return Flux.error(new IllegalStateException("PPT 大纲为空"));
                    context.instances().updateOutline(inst.getId(), outline.toString(), PptInstStatus.SCHEMA);
                    return Flux.empty();
                }));
    }
}
