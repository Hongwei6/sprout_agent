package com.sprout.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFileToolsTest {
    @TempDir Path directory;

    @Test
    void createsWorkspaceAndPersistsFilesThroughReadEditListAndGlob() throws Exception {
        Path workspace = directory.resolve("workspace");
        WorkspaceFileTools tools = new WorkspaceFileTools(workspace);
        assertTrue(Files.isDirectory(workspace));
        Files.createDirectory(workspace.resolve("notes"));

        tools.writeFile("notes/hello.txt", "first\nsecond\nthird");
        assertEquals("second\nthird", tools.readFile("notes/hello.txt", 1, 2));
        tools.editFile("notes/hello.txt", "second", "edited", null);
        assertEquals("first\nedited\nthird", Files.readString(workspace.resolve("notes/hello.txt")));
        assertEquals("first\nedited\nthird", tools.readFile("notes/hello.txt", null, null));
        tools.writeFile("notes/blank.txt", "\nsecond\n");
        assertEquals("\nsecond\n", tools.readFile("notes/blank.txt", null, null));
        assertTrue(tools.listFiles(".").contains("notes/"));
        assertTrue(tools.listFiles("notes").contains("hello.txt"));
        assertTrue(tools.globFiles("**/*.txt").contains("notes/hello.txt"));
        assertEquals("first\nedited\nthird",
                new String(tools.readBytes("notes/hello.txt", 1024), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(java.util.List.of("notes/blank.txt", "notes/hello.txt"), tools.regularFiles(".", 10, 2));
        assertEquals(java.util.List.of("notes/blank.txt", "notes/hello.txt"), tools.regularFiles("notes", 10, 1));
        assertThrows(IllegalArgumentException.class, () -> tools.writeFile("notes/hello.txt", "overwrite"));
        assertEquals("first\nedited\nthird", Files.readString(workspace.resolve("notes/hello.txt")));
    }

    @Test
    void rejectsAbsoluteTraversalAndSymlinkPathsForEveryOperation() throws Exception {
        Path workspace = directory.resolve("workspace");
        Path outside = directory.resolve("outside.txt");
        Files.writeString(outside, "SECRET");
        WorkspaceFileTools tools = new WorkspaceFileTools(workspace);
        Files.writeString(workspace.resolve("inside.txt"), "safe");
        Files.createSymbolicLink(workspace.resolve("link.txt"), outside);
        Files.createSymbolicLink(workspace.resolve("linkdir"), directory);

        for (String path : new String[] {"../outside.txt", "nested/../../outside.txt", outside.toString()}) {
            assertThrows(IllegalArgumentException.class, () -> tools.readFile(path, null, null));
            assertThrows(IllegalArgumentException.class, () -> tools.writeFile(path, "bad"));
            assertThrows(IllegalArgumentException.class, () -> tools.editFile(path, "SECRET", "bad", null));
            assertThrows(IllegalArgumentException.class, () -> tools.listFiles(path));
            assertThrows(IllegalArgumentException.class, () -> tools.globFiles(path));
        }
        assertThrows(IllegalArgumentException.class, () -> tools.readFile("link.txt", null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.editFile("link.txt", "SECRET", "bad", null));
        assertThrows(IllegalArgumentException.class, () -> tools.readBytes("link.txt", 1024));
        assertThrows(IllegalArgumentException.class, () -> tools.writeFile("link.txt", "bad"));
        assertThrows(RuntimeException.class, () -> tools.readFile("linkdir/outside.txt", null, null));
        assertThrows(RuntimeException.class, () -> tools.writeFile("linkdir/new.txt", "bad"));
        assertThrows(RuntimeException.class, () -> tools.listFiles("linkdir"));
        assertThrows(RuntimeException.class, () -> tools.regularFiles("linkdir", 10, 1));
        assertFalse(tools.listFiles(".").contains("link"));
        assertFalse(tools.globFiles("**").contains("link"));
        assertEquals(java.util.List.of("inside.txt"), tools.regularFiles(".", 10, 2));
        assertEquals("SECRET", Files.readString(outside));
        assertFalse(Files.exists(directory.resolve("new.txt")));
    }

    @Test
    void refusesSymbolicLinkWorkspaceRoot() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path root = Files.createSymbolicLink(directory.resolve("workspace"), outside);
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceFileTools(root));
    }

    @Test
    void editRequiresUniqueOriginalUnlessReplaceAllIsRequested() throws Exception {
        WorkspaceFileTools tools = new WorkspaceFileTools(directory.resolve("workspace"));
        tools.writeFile("repeat.txt", "foo foo");
        assertThrows(IllegalArgumentException.class, () -> tools.editFile("repeat.txt", "foo", "bar", null));
        assertEquals("foo foo", tools.readFile("repeat.txt", null, null));
        tools.editFile("repeat.txt", "foo", "bar", true);
        assertEquals("bar bar", tools.readFile("repeat.txt", null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.editFile("repeat.txt", "absent", "bar", null));
        assertThrows(IllegalArgumentException.class,
                () -> tools.editFile("repeat.txt", "b", "a".repeat(256 * 1024), true));
        assertEquals("bar bar", tools.readFile("repeat.txt", null, null));
    }

    @Test
    void filesystemRootCanReadHostPathsWithoutFollowingSymlinks() throws Exception {
        Path actual = directory.toRealPath();
        Files.writeString(actual.resolve("visible.txt"), "host-data");
        WorkspaceFileTools host = new WorkspaceFileTools(Path.of("/"));
        String relative = actual.resolve("visible.txt").toString().substring(1);
        assertEquals("host-data", host.readFile(relative, null, null));
        assertTrue(host.listFiles(actual.toString().substring(1)).contains("visible.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> host.readFile("../etc/passwd", null, null));
    }

    @Test
    void capsOutputAndWriteSizes() {
        WorkspaceFileTools tools = new WorkspaceFileTools(directory.resolve("workspace"));
        assertThrows(IllegalArgumentException.class, () -> tools.writeFile("large.txt", "a".repeat(256 * 1024 + 1)));
        tools.writeFile("long.txt", "a".repeat(20 * 1024));
        assertTrue(tools.readFile("long.txt", 0, 1).length() < 16 * 1024);
        assertThrows(IllegalArgumentException.class, () -> tools.readFile("long.txt", -1, 10));
        assertThrows(IllegalArgumentException.class, () -> tools.readFile("long.txt", 0, 201));
    }
}
