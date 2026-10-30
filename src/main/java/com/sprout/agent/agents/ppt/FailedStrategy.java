package com.sprout.agent.agents.ppt;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
class FailedStrategy implements PptStateStrategy {
    @Override public PptInstStatus getTargetStatus() { return PptInstStatus.FAILED; }

    @Override public Flux<ChatEvent> execute(AiPptInst inst, String query, PptStateStrategyContext context) {
        PptInstStatus stage = inst.getFailedStage();
        String message = stage == PptInstStatus.REQUIREMENT
                ? "PPT 需求还需补充。请回答上面的问题，我会从需求澄清阶段继续。"
                : inst.getError() != null && inst.getError().contains("安全审核")
                        ? inst.getError()
                        : "PPT 在" + (stage == null ? "当前" : stage.name())
                                + "阶段未能完成。已保存此前的进度；确认相关服务可用后，发送“继续”可从此处重试。";
        return Flux.just(new ChatEvent("delta", "\n\n" + message), new ChatEvent("done", ""));
    }
}
