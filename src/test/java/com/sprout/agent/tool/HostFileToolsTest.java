package com.sprout.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostFileToolsTest {
    @TempDir Path host;

    @Test
    void operatesOnAbsoluteHostPathsOutsideWorkspace() throws Exception {
        HostFileTools tools = new HostFileTools();
        Path workspace = Files.createDirectory(host.resolve("workspace"));
        Path elsewhere = Files.createDirectory(host.resolve("other;$(echo ignored)"));
        Path file = elsewhere.resolve("notes;$(echo literal).txt");
        assertEquals("Created " + file, tools.writeFile(file.toString(), "first\nsecond\n"));
        assertEquals("second\n", tools.readFile(file.toString(), 1, 2));
        assertEquals("Edited " + file, tools.editFile(file.toString(), "second", "found", null));
        assertEquals("first\nfound\n", tools.readFile(file.toString(), null, null));
        assertTrue(tools.listFiles(elsewhere.toString()).contains(file.getFileName().toString()));
        assertEquals(file + "\n", tools.globFiles(elsewhere.resolve("*.txt").toString()));
        assertEquals(file + ":2:found\n", tools.grep("found", file.toString(), null, null, null));
        assertEquals(file + ":2:found\n", tools.grep("found", elsewhere.toString(), "*.txt", null, null));
        Path alias = Files.createSymbolicLink(host.resolve("directory-alias"), elsewhere);
        Path aliasedFile = alias.resolve(file.getFileName());
        assertEquals("first\nfound\n", tools.readFile(aliasedFile.toString(), null, null));
        assertEquals(aliasedFile + "\n", tools.globFiles(alias.resolve("*.txt").toString()));
        assertEquals(aliasedFile + ":2:found\n", tools.grep("found", alias.toString(), "*.txt", null, null));
        assertEquals("first\nfound\n", Files.readString(file));
        assertFalse(Files.exists(workspace.resolve(file.getFileName())));
        assertThrows(IllegalArgumentException.class, () -> tools.writeFile(file.toString(), "overwrite"));
    }

    @Test
    void rejectsRelativePathsTraversalAndSymlinkTargets() throws Exception {
        HostFileTools tools = new HostFileTools();
        Path original = host.resolve("original.txt");
        Files.writeString(original, "private");
        Path link = Files.createSymbolicLink(host.resolve("link.txt"), original);
        for (String path : new String[] {"notes.txt", "../notes.txt", host + "/../notes.txt"}) {
            assertThrows(IllegalArgumentException.class, () -> tools.readFile(path, null, null));
            assertThrows(IllegalArgumentException.class, () -> tools.writeFile(path, "bad"));
            assertThrows(IllegalArgumentException.class, () -> tools.editFile(path, "x", "y", null));
            assertThrows(IllegalArgumentException.class, () -> tools.listFiles(path));
            assertThrows(IllegalArgumentException.class, () -> tools.globFiles(path));
            assertThrows(IllegalArgumentException.class, () -> tools.grep("private", path, null, null, null));
        }
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(link.toString(), null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.editFile(link.toString(), "private", "bad", null));
        assertThrows(IllegalArgumentException.class, () -> tools.writeFile(link.toString(), "bad"));
        assertThrows(IllegalArgumentException.class, () -> tools.grep("private", link.toString(), null, null, null));
        assertFalse(tools.listFiles(host.toString()).contains("link.txt"));
        assertEquals("private", Files.readString(original));
        assertThrows(IllegalArgumentException.class, () -> tools.globFiles("/*.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> tools.globFiles(host.resolve("missing/*.txt").toString()));
        assertThrows(IllegalArgumentException.class, () -> tools.readFile("/", null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.writeFile("/", "bad"));
        assertThrows(IllegalArgumentException.class, () -> tools.editFile("/", "x", "y", null));
        Path missing = host.resolve("missing");
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(missing.toString(), null, null));
        assertEquals("Created " + missing, tools.writeFile(missing.toString(), "new"));
        assertEquals("new", tools.readFile(missing.toString(), null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.editFile(missing.toString(), "x", "y", null));
    }

    @Test
    void limitsHostOutputAndRetainsExactOptionalArguments() throws Exception {
        HostFileTools tools = new HostFileTools();
        Path file = host.resolve("repeated.txt");
        tools.writeFile(file.toString(), "find\nfind\nfind\n");
        assertThrows(IllegalArgumentException.class, () -> tools.editFile(file.toString(), "find", "new", null));
        tools.editFile(file.toString(), "find", "new", true);
        assertEquals("new\nnew\nnew\n", tools.readFile(file.toString(), null, null));
        assertEquals(file + ":2:new\n", tools.grep("new", host.toString(), "*.txt", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> tools.writeFile(host.resolve("large.txt").toString(), "x".repeat(256 * 1024 + 1)));
        Path longFile = host.resolve("long.txt");
        tools.writeFile(longFile.toString(), "x".repeat(20 * 1024));
        assertTrue(tools.readFile(longFile.toString(), 0, 1).getBytes(StandardCharsets.UTF_8).length < 16 * 1024);
        assertThrows(IllegalArgumentException.class, () -> tools.grep("new", host.toString(), null, 0, 101));
    }

}
