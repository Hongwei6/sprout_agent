package com.sprout.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceExecutionToolsTest {
    @TempDir Path workspace;

    @Test
    void grepFindsSortedLinesWithGlobAndPagination() throws Exception {
        Files.createDirectories(workspace.resolve("notes"));
        Files.writeString(workspace.resolve("notes/z.txt"), "Alpha\nBeta\nAlpha again\n");
        Files.writeString(workspace.resolve("notes/a.txt"), "Alpha first\n");
        Files.writeString(workspace.resolve("notes/ignored.java"), "Alpha ignored\n");
        WorkspaceExecutionTools tool = new WorkspaceExecutionTools(workspace);

        assertEquals("notes/a.txt:1:Alpha first\nnotes/z.txt:1:Alpha\n",
                tool.grep("Alpha", "notes", "*.txt", 0, 2));
        assertEquals("notes/z.txt:3:Alpha again\n", tool.grep("Alpha", null, "**/*.txt", 2, 20));
        assertEquals("notes/z.txt:2:Beta\n", tool.grep("Beta", "notes/z.txt", null, null, null));
        assertEquals("", tool.grep("no match", null, null, null, null));
        assertEquals("notes/z.txt:2:Beta\n", tool.grep("Beta", ".", "*.txt", null, null));
    }

    @Test
    void grepHandlesAdversarialPatternsInLinearTime() throws Exception {
        Files.writeString(workspace.resolve("adversarial.txt"), "a".repeat(2048) + "!");
        WorkspaceExecutionTools tool = new WorkspaceExecutionTools(workspace);
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertEquals("", tool.grep("(a+)+$", "adversarial.txt", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> tool.grep("(a)\\1", null, null, null, null));
    }

    @Test
    void grepDeniesEscapeAndInvalidInputsWithoutFollowingSymlinks() throws Exception {
        Path outside = Files.createTempDirectory("grep-outside-");
        try {
            Files.writeString(outside.resolve("secret.txt"), "private-secret");
            Files.createSymbolicLink(workspace.resolve("link"), outside);
            Files.createSymbolicLink(workspace.resolve("secret.txt"), outside.resolve("secret.txt"));
            WorkspaceExecutionTools tool = new WorkspaceExecutionTools(workspace);
            for (String path : List.of("../secret.txt", "/etc", "link", "link/secret.txt", "secret.txt")) {
                assertThrows(IllegalArgumentException.class, () -> tool.grep("secret", path, null, null, null), path);
            }
            assertEquals("", tool.grep("private-secret", null, null, null, null));
            assertThrows(IllegalArgumentException.class, () -> tool.grep("[", null, null, null, null));
            assertThrows(IllegalArgumentException.class, () -> tool.grep("secret", null, "[", null, null));
            assertThrows(IllegalArgumentException.class, () -> tool.grep("secret", null, null, -1, 20));
            assertThrows(IllegalArgumentException.class, () -> tool.grep("secret", null, null, 0, 101));
            assertThrows(IllegalArgumentException.class,
                    () -> new WorkspaceExecutionTools(workspace.resolve("link")));
            Files.createDirectory(workspace.resolve("real-parent"));
            Files.createSymbolicLink(workspace.resolve("alias-parent"), workspace.resolve("real-parent"));
            WorkspaceExecutionTools fromAliasedParent = new WorkspaceExecutionTools(workspace.resolve("alias-parent/nested"));
            assertEquals("", fromAliasedParent.grep("anything", null, null, null, null));
        } finally {
            Files.delete(outside.resolve("secret.txt"));
            Files.delete(outside);
        }
    }

    @Test
    void grepBoundsTraversalAndOutput() throws Exception {
        Files.writeString(workspace.resolve("big.txt"), "x".repeat(256 * 1024 + 1));
        Files.writeString(workspace.resolve("small.txt"), "find\n".repeat(100));
        WorkspaceExecutionTools tool = new WorkspaceExecutionTools(workspace);
        String hits = tool.grep("find", null, null, 0, 100);
        assertTrue(hits.contains("small.txt:1:find"));
        assertTrue(hits.contains("[skipped 1 files larger than 256 KiB]"));
        Files.writeString(workspace.resolve("many.txt"), ("match " + "a".repeat(4090) + "\n").repeat(10));
        String bounded = tool.grep("match", "many.txt", null, 0, 100);
        assertTrue(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 16 * 1024);
        assertTrue(bounded.contains("[output truncated]"));
        for (int i = 0; i < 2_001; i++) Files.writeString(workspace.resolve("item-" + i), "");
        assertThrows(RuntimeException.class, () -> tool.grep("find", null, null, 0, 10));
    }

}
