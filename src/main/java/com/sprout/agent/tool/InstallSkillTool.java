package com.sprout.agent.tool;

import com.sprout.agent.agents.skills.SkillInstaller;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public final class InstallSkillTool {
    private final SkillInstaller installer;

    public InstallSkillTool(SkillInstaller installer) {
        this.installer = installer;
    }

    @Tool(name = "install_skill", description = "用户明确要求安装且提供公开 HTTPS 技能链接时安装到本地。GitHub 仓库包含多个技能时，请将用户指定的名称传给 skillName，不要猜测目录路径。安装 GitHub 技能的完整目录（含脚本）；也支持 SKILL.md 和单技能 ZIP 直链。不覆盖同名技能。")
    public String install(@ToolParam(description = "技能的公开 HTTPS 链接（GitHub 仓库/目录页、SKILL.md 或 ZIP 直链）") String url,
            @ToolParam(description = "用户指定的技能名，例如 pptx；未指定时留空", required = false) String skillName) {
        var skill = installer.install(url, skillName);
        return "已安装技能 " + skill.name() + "：" + skill.description()
                + "。请先使用 read_skill 读取其指南；仅能使用当前已授权工具运行脚本。";
    }
}
