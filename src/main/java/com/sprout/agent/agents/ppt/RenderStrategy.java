package com.sprout.agent.agents.ppt;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
class RenderStrategy implements PptStateStrategy {
    private final PptRenderService renderer;

    RenderStrategy(PptRenderService renderer) { this.renderer = renderer; }

    @Override public PptInstStatus getTargetStatus() { return PptInstStatus.RENDER; }

    @Override public Flux<ChatEvent> execute(AiPptInst inst, String query, PptStateStrategyContext context) {
        return Flux.just(new ChatEvent("thinking", "正在渲染 PPT 文件并保存到 MinIO"))
                .concatWith(Flux.defer(() -> {
                    renderer.render(inst);
                    if (!context.active().getAsBoolean()) return Flux.empty();
                    context.instances().updateStatus(inst.getId(), PptInstStatus.SUCCESS);
                    return Flux.just(new ChatEvent("thinking", "PPT 文件已生成"),
                            new ChatEvent("delta", "[下载 PPT 文件](/api/ppt/conversations/"
                                    + inst.getConversationId() + "/download)"));
                }));
    }
}
