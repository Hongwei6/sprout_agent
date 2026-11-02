package com.sprout.agent.tool;

import com.sprout.agent.agents.skills.SkillRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public final class ReadSkillTool {
    private final SkillRegistry registry;

    public ReadSkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "read_skill", description = "按技能名称加载完整的 SKILL.md 使用指南。技能不是工具；先读取指南，再使用当前实际可用的工具完成任务。")
    public String readSkill(@ToolParam(description = "可用技能列表中的技能名称") String skill) {
        return registry.readSkill(skill);
    }
}
