package com.sprout.agent.agents.ppt;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PptIntentRecognizer {
    private static final Pattern EDIT_REQUEST = Pattern.compile(
            "^(?:修改|编辑|调整|更改|改动|改一下|优化|润色|替换|删除|增删|把第.{0,12}页|将第.{0,12}页|(?:please )?(?:edit|modify|revise)\\b).*"
                    + "|.*(?:刚才|之前|现有|已有|上一版).{0,20}(?:ppt|幻灯片|演示文稿).{0,15}(?:改|调整|编辑|优化).*",
            Pattern.DOTALL);
    private static final Pattern REQUIREMENT_EDIT = Pattern.compile(
            "^(?:修改|调整|更改|补充).*(?:需求|主题|内容).*", Pattern.DOTALL);
    private static final Pattern NEW_REQUEST = Pattern.compile(
            ".*(?:新建|重新生成|重做|再做一份|另做一份|另外做一份|创建新|新\\s+)(?:.{0,30})?(?:ppt|幻灯片|演示文稿).*",
            Pattern.DOTALL);
    private final PptInstService instances;

    public PptIntentRecognizer(PptInstService instances) {
        this.instances = instances;
    }

    public PptIntentResult recognize(String conversationId, String query) {
        AiPptInst latest = conversationId == null ? null : instances.getLatestInst(conversationId);
        String input = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        boolean clarifying = latest != null && (latest.getStatus() == PptInstStatus.REQUIREMENT
                || latest.getStatus() == PptInstStatus.FAILED
                        && latest.getFailedStage() == PptInstStatus.REQUIREMENT);
        boolean modification = EDIT_REQUEST.matcher(input).matches()
                && !(clarifying && REQUIREMENT_EDIT.matcher(input).matches());
        if (modification && (latest == null || latest.getStatus() != PptInstStatus.SUCCESS)) {
            return new PptIntentResult(PptIntent.MODIFY_PPT, "当前没有已完成、可供修改的 PPT；请先完成 PPT 制作");
        }
        if (modification) {
            return new PptIntentResult(PptIntent.MODIFY_PPT, "修改已有 PPT");
        }
        if (latest == null || NEW_REQUEST.matcher(input).matches()) {
            return new PptIntentResult(PptIntent.CREATE_PPT, "创建新的 PPT");
        }
        if (latest.getStatus() == PptInstStatus.SUCCESS) {
            if (input.contains("继续") || input.contains("恢复") || input.contains("重试")
                    || input.contains("resume") || input.contains("retry")) {
                return new PptIntentResult(PptIntent.RESUME_PPT, "PPT 已完成");
            }
            return new PptIntentResult(PptIntent.CREATE_PPT, "创建新的 PPT");
        }
        return new PptIntentResult(PptIntent.RESUME_PPT, "继续未完成的 PPT");
    }

    public enum PptIntent {
        CREATE_PPT, MODIFY_PPT, RESUME_PPT
    }

    public record PptIntentResult(PptIntent intent, String reason) {
    }
}
