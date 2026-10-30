package com.sprout.agent.agents.ppt;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PptStateStrategyFactory {
    private final Map<PptInstStatus, PptStateStrategy> strategies = new EnumMap<>(PptInstStatus.class);

    PptStateStrategyFactory(List<PptStateStrategy> available) {
        for (PptStateStrategy strategy : available) {
            if (strategies.putIfAbsent(strategy.getTargetStatus(), strategy) != null) {
                throw new IllegalStateException("重复的 PPT 状态策略：" + strategy.getTargetStatus());
            }
        }
    }

    PptStateStrategy require(PptInstStatus status) {
        PptStateStrategy strategy = strategies.get(status);
        if (strategy == null) throw new IllegalArgumentException("PPT 阶段暂不支持执行：" + status);
        return strategy;
    }
}
