package com.sprout.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sprout.agent.agents.skills.SkillRegistry;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunSkillScriptToolTest {
    @TempDir Path directory;

    @Test
    void onlyExistingPythonFilesCanReachDockerAndSandboxFlagsAreMandatory() throws Exception {
        SkillRegistry registry = new SkillRegistry(directory);
        registry.installArchive("review", java.util.Map.of("SKILL.md", "Review notes".getBytes(),
                "scripts/check.py", "print('checked')".getBytes()));
        AtomicReference<List<String>> invoked = new AtomicReference<>();
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream("checked".getBytes()));
        when(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);
        RunSkillScriptTool tool = new RunSkillScriptTool(registry, command -> {
            invoked.set(command);
            return process;
        });
        for (String path : List.of("../secret.py", "scripts/../../secret.py", "/tmp/secret.py",
                "scripts/missing.py", "scripts/check.sh", "scripts/check.py;cat /etc/passwd")) {
            assertThrows(IllegalArgumentException.class, () -> tool.run("review", path, ""), path);
        }
        assertEquals(null, invoked.get());
        assertTrue(tool.run("review", "scripts/check.py", "hello; echo secret").contains("checked"));
        List<String> command = invoked.get();
        assertEquals("docker", command.getFirst());
        for (String flag : List.of("--network", "none", "--read-only", "--cap-drop", "ALL",
                "no-new-privileges", "--memory", "128m", "--pids-limit", "32", "--cpus", "0.5",
                "--user", "65534:65534", "--tmpfs", "--mount", "--pull", "never")) {
            assertTrue(command.contains(flag), flag);
        }
        assertTrue(command.contains("type=bind,src=" + directory.resolve("review").toRealPath() + ",dst=/skill,readonly"));
        assertEquals("hello; echo secret", command.getLast());
        assertFalse(command.contains("sh"));
    }
    @Test
    void capsOutputAndExecutionTime() throws Exception {
        SkillRegistry registry = new SkillRegistry(directory);
        registry.installArchive("review", java.util.Map.of("SKILL.md", "Review notes".getBytes(),
                "check.py", "print('hello')".getBytes()));
        Process noisy = mock(Process.class);
        when(noisy.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[16 * 1024 + 1]));
        when(noisy.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        assertThrows(IllegalStateException.class,
                () -> new RunSkillScriptTool(registry, command -> noisy).run("review", "check.py", ""));

        Process slow = mock(Process.class);
        when(slow.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(slow.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> new RunSkillScriptTool(registry, command -> slow).run("review", "check.py", ""));
    }


    @Test
    void symlinkScriptAndIntermediateFolderCannotReachDocker() throws Exception {
        SkillRegistry registry = new SkillRegistry(directory);
        registry.install("review", "Review notes");
        Path root = directory.resolve("review");
        Files.writeString(directory.resolve("escape.py"), "print('outside')");
        Files.createSymbolicLink(root.resolve("escape.py"), directory.resolve("escape.py"));
        Files.createSymbolicLink(root.resolve("scripts"), directory);
        RunSkillScriptTool tool = new RunSkillScriptTool(registry, command -> {
            throw new AssertionError("Docker was launched for a symlink");
        });
        assertThrows(IllegalArgumentException.class, () -> tool.run("review", "escape.py", ""));
        assertThrows(IllegalArgumentException.class, () -> tool.run("review", "scripts/escape.py", ""));
    }
}
