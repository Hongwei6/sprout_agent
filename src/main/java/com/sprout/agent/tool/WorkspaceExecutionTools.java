package com.sprout.agent.tool;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Searches a bounded directory tree without following symlinks. */
public final class WorkspaceExecutionTools {
    private static final int MAX_OUTPUT = 16 * 1024;
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_SEARCH_BYTES = 8 * 1024 * 1024;
    private final Path workspace;

    public WorkspaceExecutionTools(Path workspace) {
        Path root = workspace.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("Workspace cannot be a symlink");
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Workspace must be a real directory");
            }
            this.workspace = root.toRealPath();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create workspace", ex);
        }
    }

    @Tool(name = "grep", description = "Search workspace files with a linear-time RE2 regular expression. Results are path:line:content; search and output are bounded.")
    public String grep(@ToolParam(description = "RE2 regular expression (no backreferences or lookbehind)") String regex,
            @ToolParam(description = "Relative workspace directory or file; empty searches the whole workspace", required = false) String path,
            @ToolParam(description = "Optional filename/path glob (*, **, ? wildcards only), e.g. *.java or **/*.java", required = false) String glob,
            @ToolParam(description = "Number of matching lines to skip (default 0)", required = false) Integer offset,
            @ToolParam(description = "Maximum matching lines to return, 1 to 100 (default 50)", required = false) Integer limit) {
        if (regex == null || regex.isEmpty() || regex.length() > 1024) {
            throw new IllegalArgumentException("Regex must contain 1 to 1024 characters");
        }
        int skip = offset == null ? 0 : offset;
        int count = limit == null ? 50 : limit;
        if (skip < 0 || skip > 10_000 || count < 1 || count > 100) {
            throw new IllegalArgumentException("Offset must be 0..10000 and limit must be 1..100");
        }
        Pattern pattern;
        Pattern matcher = null;
        try {
            pattern = Pattern.compile(regex);
            if (glob != null && !glob.isBlank()) matcher = compileGlob(glob);
        } catch (PatternSyntaxException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid regular expression or glob", ex);
        }
        Path root = resolve(path, false);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Search path does not exist or is not a regular file/directory");
        }
        String relativeRoot = workspace.relativize(root).toString();
        WorkspaceFileTools files = new WorkspaceFileTools(workspace);
        List<String> paths = Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)
                ? List.of(relativeRoot) : files.regularFiles(relativeRoot.isEmpty() ? "." : relativeRoot, MAX_ENTRIES, 8);
            StringBuilder result = new StringBuilder();
            int resultBytes = 0;
            int matched = 0;
            int totalBytes = 0;
            int oversized = 0;
            for (String file : paths) {
                Path relative = Path.of(file);
                if (matcher != null && !matcher.matcher(file.replace('\\', '/')).matches()
                        && !matcher.matcher(relative.getFileName().toString()).matches()) continue;
                byte[] bytes;
                try {
                    bytes = files.readBytes(file, MAX_FILE_BYTES);
                } catch (IllegalArgumentException ex) {
                    if (ex.getMessage() == null || !ex.getMessage().contains("exceeds")) throw ex;
                    oversized++;
                    continue;
                }
                    totalBytes += bytes.length;
                    if (totalBytes > MAX_SEARCH_BYTES) throw new IllegalStateException("Search exceeds 8 MiB");
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (text.indexOf('\0') >= 0) continue;
                    int lineNumber = 0;
                    for (String line : text.split("\\R", -1)) {
                        lineNumber++;
                        // Do not feed arbitrarily long input to an untrusted regular expression.
                        if (line.length() > 4096) continue;
                        if (!pattern.matcher(line).find()) continue;
                        if (matched++ < skip) continue;
                        if (matched > skip + count) break;
                        String hit = file.replace('\\', '/') + ":" + lineNumber + ":" + line + "\n";
                        int hitBytes = hit.getBytes(StandardCharsets.UTF_8).length;
                        if (resultBytes + hitBytes > MAX_OUTPUT) {
                            if (resultBytes + "[output truncated]\n".length() <= MAX_OUTPUT) result.append("[output truncated]\n");
                            return result.toString();
                        }
                        result.append(hit);
                        resultBytes += hitBytes;
                    }
                if (matched > skip + count) break;
            }
            if (oversized != 0) {
                String notice = "[skipped " + oversized + " files larger than 256 KiB]\n";
                if (resultBytes + notice.getBytes(StandardCharsets.UTF_8).length <= MAX_OUTPUT) result.append(notice);
            }
            return result.toString();
    }

    private static Pattern compileGlob(String glob) {
        if (glob.length() > 256) throw new IllegalArgumentException("Glob exceeds 256 characters");
        StringBuilder expression = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    expression.append(".*");
                    i++;
                } else {
                    expression.append("[^/]*");
                }
            } else if (c == '?') {
                expression.append("[^/]");
            } else if (c == '[' || c == ']' || c == '{' || c == '}' || c == '\\' || c == '\0') {
                throw new IllegalArgumentException("Glob supports only *, ** and ? wildcards");
            } else {
                if (".()+^$|".indexOf(c) >= 0) expression.append('\\');
                expression.append(c);
            }
        }
        return Pattern.compile(expression.append('$').toString());
    }


    private Path resolve(String relative, boolean directory) {
        if (Files.isSymbolicLink(workspace) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Workspace is not a real directory");
        }
        if (relative == null || relative.isBlank() || relative.equals(".")) return workspace;
        if (relative.indexOf('\0') >= 0 || relative.length() > 1024) throw new IllegalArgumentException("Invalid workspace path");
        Path path = Path.of(relative);
        if (path.isAbsolute()) throw new IllegalArgumentException("Absolute paths are forbidden");
        for (Path segment : path) {
            if (segment.toString().equals("..") || segment.toString().equals(".")) {
                throw new IllegalArgumentException("Parent/current path segments are forbidden");
            }
        }
        Path target = workspace.resolve(path).normalize();
        if (!target.startsWith(workspace)) throw new IllegalArgumentException("Path escapes workspace");
        ensureNoSymlinks(target);
        if (directory && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Working directory must be a real directory");
        }
        return target;
    }

    private void ensureNoSymlinks(Path target) {
        for (Path part = target; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IllegalArgumentException("Symlinks are forbidden in workspace paths");
        }
    }

}
