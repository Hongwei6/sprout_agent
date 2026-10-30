package com.sprout.agent.agents.ppt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sprout.agent.mapper.AiPptInstMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PptInstService {
    private final AiPptInstMapper mapper;

    public PptInstService(AiPptInstMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public AiPptInst createInst(String conversationId, String query) {
        AiPptInst inst = new AiPptInst();
        inst.setConversationId(Objects.requireNonNull(conversationId, "conversationId"));
        inst.setQuery(Objects.requireNonNull(query, "query"));
        inst.setStatus(PptInstStatus.INIT);
        inst.setCreatedAt(LocalDateTime.now());
        inst.setUpdatedAt(inst.getCreatedAt());
        mapper.insert(inst);
        return inst;
    }

    public AiPptInst getLatestInst(String conversationId) {
        return mapper.selectOne(new LambdaQueryWrapper<AiPptInst>()
                .eq(AiPptInst::getConversationId, conversationId)
                .orderByDesc(AiPptInst::getId)
                .last("LIMIT 1"));
    }

    public AiPptInst find(long id) {
        return mapper.selectById(id);
    }

    public void updateRequirement(long id, String requirement, PptInstStatus status) {
        update(id, newUpdate(id, status).set(AiPptInst::getRequirement, requirement));
    }

    public void updateSearchInfo(long id, String searchInfo, PptInstStatus status) {
        update(id, newUpdate(id, status).set(AiPptInst::getSearchInfo, searchInfo));
    }

    public void updateTemplate(long id, String templateCode, PptInstStatus status) {
        update(id, newUpdate(id, status).set(AiPptInst::getTemplateCode, templateCode));
    }

    public void updateOutline(long id, String outline, PptInstStatus status) {
        update(id, newUpdate(id, status).set(AiPptInst::getOutline, outline));
    }

    public void updatePptSchema(long id, String json, PptInstStatus status) {
        update(id, newUpdate(id, status).set(AiPptInst::getPptSchema, json));
    }

    public void updateStatus(long id, PptInstStatus status) {
        update(id, newUpdate(id, status));
    }

    /** Keep all completed checkpoints when a stage fails, so a later request can resume it. */
    public void updateError(long id, String error, PptInstStatus failedStage) {
        if (failedStage == PptInstStatus.FAILED || failedStage == PptInstStatus.SUCCESS) {
            throw new IllegalArgumentException("failedStage must identify a pending phase");
        }
        update(id, new LambdaUpdateWrapper<AiPptInst>()
                .eq(AiPptInst::getId, id)
                .eq(AiPptInst::getStatus, Objects.requireNonNull(failedStage, "failedStage"))
                .set(AiPptInst::getStatus, PptInstStatus.FAILED)
                .set(AiPptInst::getFailedStage, failedStage)
                .set(AiPptInst::getError, error)
                .set(AiPptInst::getUpdatedAt, LocalDateTime.now()));
    }

    @Transactional
    public AiPptInst resume(long id) {
        AiPptInst inst = find(id);
        if (inst == null || inst.getStatus() != PptInstStatus.FAILED || inst.getFailedStage() == null) {
            throw new IllegalStateException("PPT instance is not resumable: " + id);
        }
        update(id, new LambdaUpdateWrapper<AiPptInst>()
                .eq(AiPptInst::getId, id)
                .eq(AiPptInst::getStatus, PptInstStatus.FAILED)
                .eq(AiPptInst::getFailedStage, inst.getFailedStage())
                .set(AiPptInst::getStatus, inst.getFailedStage())
                .set(AiPptInst::getFailedStage, null)
                .set(AiPptInst::getError, null)
                .set(AiPptInst::getUpdatedAt, LocalDateTime.now()));
        return find(id);
    }

    private LambdaUpdateWrapper<AiPptInst> newUpdate(long id, PptInstStatus status) {
        if (status == PptInstStatus.FAILED) {
            throw new IllegalArgumentException("Use updateError to record the failed stage");
        }
        return new LambdaUpdateWrapper<AiPptInst>()
                .eq(AiPptInst::getId, id)
                .ne(AiPptInst::getStatus, PptInstStatus.FAILED)
                .set(AiPptInst::getStatus, Objects.requireNonNull(status, "status"))
                .set(AiPptInst::getUpdatedAt, LocalDateTime.now());
    }

    private void update(long id, LambdaUpdateWrapper<AiPptInst> update) {
        if (mapper.update(null, update) != 1) {
            throw new IllegalStateException("PPT instance missing or changed state: " + id);
        }
    }
}
