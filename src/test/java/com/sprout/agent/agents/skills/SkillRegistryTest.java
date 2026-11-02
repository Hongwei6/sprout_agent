package com.sprout.agent.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sprout.agent.tool.ReadSkillTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.support.ToolCallbacks;

class SkillRegistryTest {
    @TempDir Path directory;

    @Test
    void exposesDescriptionsButLoadsCurrentInstructionsOnlyWhenCalled() throws Exception {
        Path skill = Files.createDirectory(directory.resolve("verify"));
        Path file = skill.resolve("SKILL.md");
        Files.writeString(file, "---\nname: verify\ndescription: 检查公开事实\n---\n# Instructions\nold");
        SkillRegistry registry = new SkillRegistry(directory);

        assertEquals("verify", registry.list().getFirst().name());
        assertEquals("检查公开事实", registry.list().getFirst().description());
        Files.writeString(file, "---\nname: verify\ndescription: 检查公开事实\n---\n# Instructions\nnew");
        String result = ToolCallbacks.from(new ReadSkillTool(registry))[0].call("{\"skill\":\"verify\"}");
        assertTrue(result.contains("new"));
        assertThrows(IllegalArgumentException.class, () -> registry.readSkill("../other"));
    }

    @Test
    void supportsPlainMarkdownAndRejectsOversizedSkill() throws Exception {
        Path skill = Files.createDirectory(directory.resolve("guide"));
        Path file = skill.resolve("SKILL.md");
        Files.writeString(file, "# Guide\n\nFirst paragraph describes the skill.\nNext step");
        SkillRegistry registry = new SkillRegistry(directory);
        assertEquals("First paragraph describes the skill.", registry.list().getFirst().description());
        Files.writeString(file, "x".repeat(128 * 1024 + 1));
        assertThrows(IllegalStateException.class, () -> registry.readSkill("guide"));
    }

    @Test
    void installsArchiveOnlyWithValidPathsAndCleansUpOnFailure() throws Exception {
        SkillRegistry registry = new SkillRegistry(directory);
        registry.installArchive("review", Map.of("SKILL.md", "Review notes".getBytes(),
                "scripts/check.py", "print(1)".getBytes()));
        assertEquals("print(1)", Files.readString(directory.resolve("review/scripts/check.py")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.installArchive("review", Map.of("SKILL.md", "again".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> registry.installArchive("new",
                Map.of("SKILL.md", "Notes".getBytes(), "other/../../escape", "x".getBytes())));
        assertEquals(1, registry.list().size());
    }
}
