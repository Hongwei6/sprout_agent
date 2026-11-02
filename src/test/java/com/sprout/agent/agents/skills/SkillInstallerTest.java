package com.sprout.agent.agents.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import com.sprout.agent.service.UploadedFileService;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

class SkillInstallerTest {
    @TempDir Path directory;

    private SkillInstaller installer(byte[] payload) {
        return new SkillInstaller(new SkillRegistry(directory), (uri, ip) -> payload,
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
    }

    @Test
    void directHttpsMarkdownReloadWithoutOverwrite() {
        SkillInstaller installer = installer("---\ndescription: 检查代码\n---\n# Guide\nSteps".getBytes(StandardCharsets.UTF_8));
        assertEquals("review", installer.install("https://example.com/skills/review/SKILL.md").name());
        assertThrows(IllegalArgumentException.class,
                () -> installer.install("https://example.com/skills/review/SKILL.md"));
        assertTrue(new SkillRegistry(directory).readSkill("review").contains("Steps"));
    }

    @Test
    void repoPageLinkDiscoversAndInstallsTheSingleSkill() throws Exception {
        SkillInstaller installer = new SkillInstaller(new SkillRegistry(directory), (uri, ip) -> {
            assertEquals("https://codeload.github.com/team/tools/zip/HEAD", uri.toString());
            try {
                return archive(Map.of("tools-HEAD/README.md", "info",
                    "tools-HEAD/skills/lint/SKILL.md", "---\ndescription: Lint code\n---\nGuide",
                    "tools-HEAD/skills/lint/scripts/run.py", "print('lint')"));
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }, host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        assertEquals("lint", installer.install("https://github.com/team/tools/").name());
        assertEquals("print('lint')", Files.readString(directory.resolve("lint/scripts/run.py")));
    }

    @Test
    void selectsNamedSkillFromRepositoryAndInstallsItsAssets() throws Exception {
        byte[] zip = archive(Map.of(
                "tools-HEAD/skills/pdf/SKILL.md", "---\ndescription: PDF\n---\n",
                "tools-HEAD/skills/pptx/SKILL.md", "---\nname: pptx\ndescription: Slides\n---\nGuide",
                "tools-HEAD/skills/pptx/scripts/validate.py", "print('validated')"));
        SkillInstaller installer = new SkillInstaller(new SkillRegistry(directory), (uri, ip) -> zip,
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        assertEquals("pptx", installer.install("https://github.com/team/tools", "pptx").name());
        assertEquals("print('validated')", Files.readString(directory.resolve("pptx/scripts/validate.py")));
        assertFalse(Files.exists(directory.resolve("pdf")));
    }

    @Test
    void multiSkillRepositoryAsksForASpecificLinkInsteadOfGuessing() throws Exception {
        byte[] zip = archive(Map.of("tools-main/skills/a/SKILL.md", "desc A",
                "tools-main/skills/b/SKILL.md", "desc B"));
        SkillInstaller installer = new SkillInstaller(new SkillRegistry(directory), (uri, ip) -> zip,
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> installer.install("https://github.com/team/tools/tree/main"));
        assertTrue(ex.getMessage().contains("2 个技能"));
    }

    @Test
    void archiveInstallsRealScriptsAndReloadsRegistry() throws Exception {
        byte[] zip = archive(Map.of("review/SKILL.md", "---\ndescription: review\n---\nInstructions",
                "review/scripts/check.py", "print('checked')"));
        SkillRegistry registry = new SkillRegistry(directory);
        assertEquals("review", new SkillInstaller(registry, (uri, ip) -> zip,
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")})
                .install("https://example.org/tool.zip").name());
        assertEquals("print('checked')", Files.readString(directory.resolve("review/scripts/check.py")));
        assertEquals(1, registry.list().size());
        assertThrows(IllegalArgumentException.class, () -> installer(zip).install("https://example.org/tool.zip"));
    }
    @Test
    void acceptsRootSkillMarkdownWithDeclaredName() {
        SkillInstaller installer = installer("---\nname: root-review\ndescription: Review files\n---\nInstructions"
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("root-review", installer.install("https://example.com/SKILL.md").name());
    }


    @Test
    void blocksLocalReservedDnsRebindingAndMalformedLinksBeforeDownload() throws Exception {
        SkillInstaller installer = new SkillInstaller(new SkillRegistry(directory), (uri, ip) -> {
            throw new AssertionError("untrusted download occurred");
        }, host -> new InetAddress[] {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")});
        assertThrows(IllegalArgumentException.class, () -> installer.install("https://example.com/review/SKILL.md"));
        for (String url : new String[] {"http://127.0.0.1/review/SKILL.md", "https://127.0.0.1/review/SKILL.md",
                "https://169.254.169.254/review/SKILL.md", "https://host/review/%2e%2e/SKILL.md",
                "https://host/review/../SKILL.md", "https://user:pass@host/review/SKILL.md",
                "https://host/review/SKILL.md?redirect=https://internal"}) {
            assertThrows(IllegalArgumentException.class, () -> installer.install(url), url);
        }
        assertFalse(SkillInstaller.publicAddress(InetAddress.getByName("100.64.0.1")));
        assertTrue(SkillInstaller.publicAddress(InetAddress.getByName("198.18.0.1")));
        assertFalse(SkillInstaller.publicAddress(InetAddress.getByName("198.51.100.2")));
        assertFalse(SkillInstaller.publicAddress(InetAddress.getByName("2001:db8::1")));
    }

    @Test
    void rejectsTraversalSymlinksBombsAndLeavesNoPartialSkill() throws Exception {
        for (String entry : new String[] {"review/../escape", "review/scripts/../../escape",
                "review/dir\\bad.py"}) {
            byte[] zip = archive(Map.of("review/SKILL.md", "Review notes", entry, "evil"));
            assertThrows(IllegalArgumentException.class, () -> installer(zip).install("https://example.org/file.zip"));
            assertFalse(Files.exists(directory.resolve("review")));
        }
        byte[] huge = archive(Map.of("review/SKILL.md", "Review notes",
                "review/scripts/large.py", "x".repeat(10 * 1024 * 1024 + 1)));
        assertThrows(IllegalArgumentException.class, () -> installer(huge).install("https://example.org/file.zip"));
        assertFalse(Files.exists(directory.resolve("review")));

        byte[] link = archive(Map.of("review/SKILL.md", "Review notes", "review/scripts/run.py", "secret"));
        int central = findCentralEntry(link, "review/scripts/run.py");
        ByteBuffer.wrap(link).order(ByteOrder.LITTLE_ENDIAN).putInt(central + 38, 0120777 << 16);
        assertThrows(IllegalArgumentException.class, () -> installer(link).install("https://example.org/file.zip"));
        assertFalse(Files.exists(directory.resolve("review")));
    }

    @Test
    void skillsAgentExposesExecutionOnlyInSkillsMode() {
        SkillRegistry registry = new SkillRegistry(directory);
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new ToolCallback[0]);
        SkillsReactAgent agent = new SkillsReactAgent(mock(ObjectProvider.class),
                mock(ConversationService.class), mock(AgentTaskManager.class),
                mock(UploadedFileService.class), new ObjectMapper(), provider, registry,
                new SkillInstaller(registry), "api-key", "model", 5, 20, true);
        assertEquals("read_skill", agent.toolCallbacks(null)[0].getToolDefinition().name());
        assertEquals("install_skill", agent.toolCallbacks(null)[1].getToolDefinition().name());
        assertEquals("run_skill_script", agent.toolCallbacks(null)[2].getToolDefinition().name());
        assertTrue(agent.systemPrompt().contains("不能执行宿主机命令"));
    }

    private static byte[] archive(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static int findCentralEntry(byte[] zip, String name) {
        ByteBuffer bytes = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < zip.length - 46; i++) {
            if (bytes.getInt(i) == 0x02014b50) {
                int length = Short.toUnsignedInt(bytes.getShort(i + 28));
                if (new String(zip, i + 46, length, StandardCharsets.UTF_8).equals(name)) return i;
            }
        }
        throw new AssertionError("missing ZIP entry");
    }
}
