package com.sprout.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Bounded host filesystem access using the service OS account. */
public final class HostFileTools {
    @Tool(name = "read_file", description = "Read an absolute host UTF-8 file path. File size and output are bounded.")
    public String readFile(@ToolParam(description = "Absolute host file path") String path,
            @ToolParam(description = "Zero-based line offset; defaults to 0", required = false) Integer offset,
            @ToolParam(description = "Number of lines (1-200); defaults to 100", required = false) Integer limit) {
        Path file = absoluteFile(path);
        return fileTools(file.getParent()).readFile(file.getFileName().toString(), offset, limit);
    }

    @Tool(name = "write_file", description = "Create a new UTF-8 file at an absolute host path in an existing directory; never overwrites. Content limit: 256 KiB.")
    public String writeFile(@ToolParam(description = "Absolute host path for the new file") String path,
            @ToolParam(description = "UTF-8 content (at most 256 KiB)") String content) {
        Path file = absoluteFile(path);
        fileTools(file.getParent()).writeFile(file.getFileName().toString(), content);
        return "Created " + file;
    }

    @Tool(name = "edit_file", description = "Edit a host UTF-8 file at an absolute path; replace unique text or all occurrences. Maximum resulting size: 256 KiB.")
    public String editFile(@ToolParam(description = "Absolute host file path") String path,
            @ToolParam(description = "Non-empty original text") String oldString,
            @ToolParam(description = "Replacement text") String newString,
            @ToolParam(description = "Replace all occurrences instead of requiring exactly one", required = false) Boolean replaceAll) {
        Path file = absoluteFile(path);
        fileTools(file.getParent()).editFile(file.getFileName().toString(), oldString, newString, replaceAll);
        return "Edited " + file;
    }

    @Tool(name = "list_files", description = "List up to 100 direct entries of an absolute host directory; symbolic links are omitted.")
    public String listFiles(@ToolParam(description = "Absolute host directory path") String path) {
        Path directory = existingDirectory(absolute(path));
        return new WorkspaceFileTools(directory).listFiles(".");
    }

    @Tool(name = "glob_files", description = "Find files matching an absolute host glob under an existing directory prefix. Search is bounded to 8 levels and 2000 entries.")
    public String globFiles(@ToolParam(description = "Absolute host glob, e.g. /home/user/docs/**/*.txt; an existing directory prefix is required") String pattern) {
        absolute(pattern);
        int wildcard = firstWildcard(pattern);
        int separator = pattern.lastIndexOf('/', wildcard < 0 ? pattern.length() - 1 : wildcard);
        if (separator < 1) throw new IllegalArgumentException("Glob requires an existing directory prefix below the filesystem root");
        Path visibleRoot = Path.of(pattern.substring(0, separator));
        Path directory = existingDirectory(visibleRoot);
        String matches = new WorkspaceFileTools(directory).globFiles(pattern.substring(separator + 1));
        return prefixLines(visibleRoot, matches);
    }

    @Tool(name = "grep", description = "Search an absolute host file or directory with a bounded RE2 regular expression; no shell execution.")
    public String grep(@ToolParam(description = "RE2 regular expression (no backreferences or lookbehind)") String regex,
            @ToolParam(description = "Absolute host file or directory path") String path,
            @ToolParam(description = "Optional filename/path glob (*, **, ? wildcards only)", required = false) String glob,
            @ToolParam(description = "Number of matching lines to skip (default 0)", required = false) Integer offset,
            @ToolParam(description = "Maximum matching lines, 1 to 100 (default 50)", required = false) Integer limit) {
        Path target = absolute(path);
        boolean searchDirectory = Files.isDirectory(target);
        Path visibleRoot = searchDirectory ? target : target.getParent();
        Path directory = existingDirectory(visibleRoot);
        if (searchDirectory && directory.getFileName() == null) {
            throw new IllegalArgumentException("Searching the filesystem root is not supported");
        }
        String relative = searchDirectory ? "." : target.getFileName().toString();
        String matches = new WorkspaceExecutionTools(directory).grep(regex, relative, glob, offset, limit);
        return prefixLines(visibleRoot, matches);
    }

    private static WorkspaceFileTools fileTools(Path parent) {
        return new WorkspaceFileTools(existingDirectory(parent));
    }

    private static Path existingDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Host directory must already exist");
        }
        try {
            return directory.toRealPath();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Host directory is not accessible", ex);
        }
    }

    private static Path absoluteFile(String path) {
        Path file = absolute(path);
        if (file.getFileName() == null) throw new IllegalArgumentException("Expected an absolute host file path");
        return file;
    }

    private static Path absolute(String input) {
        if (input == null || input.isBlank() || input.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("An absolute host path is required");
        }
        try {
            Path path = Path.of(input);
            if (!path.isAbsolute()) throw new IllegalArgumentException("An absolute host path is required");
            for (Path component : path) {
                if (component.toString().equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed");
            }
            return path.normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Invalid host path", ex);
        }
    }

    private static int firstWildcard(String pattern) {
        for (int index = 0; index < pattern.length(); index++) {
            if ("*?[{".indexOf(pattern.charAt(index)) >= 0) return index;
        }
        return -1;
    }

    private static String prefixLines(Path directory, String lines) {
        if (lines.isEmpty()) return lines;
        String root = directory + (directory.getFileName() == null ? "" : "/");
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (String line : lines.split("(?<=\\n)", -1)) {
            if (line.isEmpty()) continue;
            String prefixed = line.equals("[output truncated]\n") || line.equals("[results truncated]")
                    || line.startsWith("[skipped ") && line.endsWith(" files larger than 256 KiB]\n")
                    ? line : root + line;
            int length = prefixed.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > 16 * 1024 - 32) {
                result.append("[output truncated]\n");
                break;
            }
            result.append(prefixed);
            bytes += length;
        }
        return result.toString();
    }

}
