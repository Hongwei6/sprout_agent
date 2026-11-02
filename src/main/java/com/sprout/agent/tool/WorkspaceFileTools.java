package com.sprout.agent.tool;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** File operations confined to one dedicated workspace (never to the host's arbitrary paths). */
public final class WorkspaceFileTools {
    private static final int MAX_WRITE_BYTES = 256 * 1024;
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final int MAX_LINES = 200;
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_SCANNED_ENTRIES = 2000;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_BRIDGE_BYTES = 5 * 1024 * 1024;
    // macOS does not expose SecureDirectoryStream: fixed Python openat calls retain descriptor-anchored NOFOLLOW traversal.
    private static final String PYTHON_BRIDGE = """
        import errno, os, stat, struct, sys

        def arg():
            length = struct.unpack(">I", sys.stdin.buffer.read(4))[0]
            if length > 2 * 1024 * 1024:
                raise ValueError("Input exceeds size limit")
            data = sys.stdin.buffer.read(length)
            if len(data) != length:
                raise ValueError("Incomplete input")
            return data.decode("utf-8")

        def parts(path):
            if path in ("", "."):
                return []
            if path.startswith("/") or len(path) > 512:
                raise ValueError("Invalid workspace-relative path")
            names = path.split("/")
            if any(name in ("", ".", "..") for name in names):
                raise ValueError("Parent traversal or invalid path")
            return names

        def open_dir(names):
            fd = os.dup(root)
            try:
                for name in names:
                    child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                    os.close(fd)
                    fd = child
                return fd
            except BaseException:
                os.close(fd)
                raise

        def file_fd(path, flags):
            names = parts(path)
            if not names:
                raise ValueError("Expected a workspace file path")
            parent = open_dir(names[:-1])
            try:
                fd = os.open(names[-1], flags | os.O_NOFOLLOW | os.O_NONBLOCK, 0o666, dir_fd=parent)
                if not stat.S_ISREG(os.fstat(fd).st_mode):
                    os.close(fd)
                    raise ValueError("File is not a regular file")
                return fd
            finally:
                os.close(parent)

        def read_data(fd, limit):
            if os.fstat(fd).st_size > limit:
                raise ValueError("File exceeds size limit")
            result = bytearray()
            while len(result) <= limit:
                chunk = os.read(fd, min(65536, limit + 1 - len(result)))
                if not chunk:
                    break
                result.extend(chunk)
            if len(result) > limit:
                raise ValueError("File exceeds size limit")
            return bytes(result)

        def entries(path, max_entries, depth):
            results = []
            seen = 0
            start = open_dir(parts(path))
            def visit(fd, prefix, level):
                nonlocal seen
                with os.scandir(fd) as stream:
                    for item in stream:
                        seen += 1
                        if seen > 2000 or len(results) > max_entries:
                            raise ValueError("Directory has too many entries")
                        info = os.stat(item.name, dir_fd=fd, follow_symlinks=False)
                        if stat.S_ISLNK(info.st_mode):
                            continue
                        name = prefix + item.name
                        if stat.S_ISREG(info.st_mode):
                            results.append(name)
                        elif stat.S_ISDIR(info.st_mode) and level < depth:
                            child = os.open(item.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                            try:
                                visit(child, name + "/", level + 1)
                            finally:
                                os.close(child)
            try:
                visit(start, "", 1)
            finally:
                os.close(start)
            if len(results) > max_entries:
                raise ValueError("Directory has too many entries")
            return sorted(results)

        try:
            if sys.argv[1] == "/":
                root = os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            else:
                parent = os.open(os.path.dirname(sys.argv[1]), os.O_RDONLY | os.O_DIRECTORY)
                try:
                    root = os.open(os.path.basename(sys.argv[1]), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
                finally:
                    os.close(parent)
            try:
                mode = sys.argv[2]
                path = arg()
                if mode in ("read", "bytes"):
                    limit = int(arg()) if mode == "bytes" else 1024 * 1024
                    fd = file_fd(path, os.O_RDONLY)
                    try:
                        data = read_data(fd, limit)
                    finally:
                        os.close(fd)
                    if mode == "read":
                        offset, count = int(arg()), int(arg())
                        lines = data.decode("utf-8").split("\\n")
                        result = bytearray()
                        emitted = 0
                        for line in lines[offset:offset + count]:
                            piece = line.removesuffix("\\r").encode("utf-8")
                            extra = (b"\\n" if emitted else b"") + piece
                            if len(result) + len(extra) > 16 * 1024 - 32:
                                result.extend(b"\\n[output truncated]")
                                break
                            result.extend(extra)
                            emitted += 1
                        data = bytes(result)
                    output = data
                elif mode == "write":
                    data = arg().encode("utf-8")
                    if len(data) > 256 * 1024:
                        raise ValueError("Content exceeds 256 KiB limit")
                    fd = file_fd(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
                    try:
                        while data:
                            data = data[os.write(fd, data):]
                    finally:
                        os.close(fd)
                    output = ("Created " + path).encode("utf-8")
                elif mode == "edit":
                    old, new, all_occurrences = arg(), arg(), arg() == "1"
                    fd = file_fd(path, os.O_RDWR)
                    try:
                        original = read_data(fd, 256 * 1024)
                        text = original.decode("utf-8")
                        occurrences = text.count(old)
                        if not occurrences:
                            raise ValueError("Original text not found")
                        if occurrences > 1 and not all_occurrences:
                            raise ValueError("Original text occurs more than once; use replaceAll")
                        if len(original) + (occurrences if all_occurrences else 1) * (len(new.encode("utf-8")) - len(old.encode("utf-8"))) > 256 * 1024:
                            raise ValueError("Edited content exceeds 256 KiB limit")
                        data = text.replace(old, new, -1 if all_occurrences else 1).encode("utf-8")
                        os.lseek(fd, 0, os.SEEK_SET)
                        position = 0
                        while position < len(data):
                            position += os.write(fd, data[position:])
                        os.ftruncate(fd, len(data))
                    finally:
                        os.close(fd)
                    output = ("Edited " + path).encode("utf-8")
                elif mode == "list":
                    fd = open_dir(parts(path))
                    try:
                        names = []
                        with os.scandir(fd) as stream:
                            for item in stream:
                                if len(names) >= 2000:
                                    raise ValueError("Directory has too many entries")
                                info = os.stat(item.name, dir_fd=fd, follow_symlinks=False)
                                if not stat.S_ISLNK(info.st_mode):
                                    names.append(item.name + ("/" if stat.S_ISDIR(info.st_mode) else ""))
                        names.sort()
                        result = bytearray()
                        emitted = 0
                        for name in names:
                            line = (name + "\\n").encode("utf-8")
                            if len(result) + len(line) > 16 * 1024 - 32 or emitted >= 100:
                                result.extend(b"[results truncated]")
                                break
                            result.extend(line)
                            emitted += 1
                        output = bytes(result)
                    finally:
                        os.close(fd)
                elif mode in ("glob", "files"):
                    max_entries, depth = (2000, 8) if mode == "glob" else (int(arg()), int(arg()))
                    output = b"\\0".join(name.encode("utf-8") for name in entries(path, max_entries, depth))
                else:
                    raise ValueError("Unknown operation")
            finally:
                os.close(root)
            sys.stdout.buffer.write(b"0" + output)
        except FileExistsError:
            sys.stdout.buffer.write(b"1File already exists")
        except FileNotFoundError:
            sys.stdout.buffer.write(b"1Workspace file or directory does not exist")
        except UnicodeError:
            sys.stdout.buffer.write(b"1File must contain valid UTF-8 text")
        except ValueError as error:
            sys.stdout.buffer.write(b"1" + str(error).encode("utf-8"))
        except OSError as error:
            if error.errno == errno.ELOOP:
                sys.stdout.buffer.write(b"1Symbolic links are not allowed")
            else:
                sys.stdout.buffer.write(b"2Workspace file operation failed")
        """;
    private final Path root;
    private final boolean secureDirectoryStreams;

    public WorkspaceFileTools(Path workspace) {
        if (workspace == null) throw new IllegalArgumentException("Workspace path is required");
        root = workspace.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("Workspace root cannot be a symbolic link");
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Workspace root must be a real directory");
            }
            Path parent = root.getParent();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent == null ? root : parent)) {
                secureDirectoryStreams = stream instanceof SecureDirectoryStream<?>;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create workspace directory");
        }
    }

    @Tool(name = "read_file", description = "Read a UTF-8 file in the dedicated workspace, optionally starting at a zero-based line offset with a line limit. Output and file size are bounded.")
    public String readFile(@ToolParam(description = "Workspace-relative file path") String path,
            @ToolParam(description = "Zero-based line offset; defaults to 0", required = false) Integer offset,
            @ToolParam(description = "Number of lines (1-200); defaults to 100", required = false) Integer limit) {
        int start = offset == null ? 0 : offset;
        int count = limit == null ? 100 : limit;
        if (start < 0 || start > 10_000 || count < 1 || count > MAX_LINES) {
            throw new IllegalArgumentException("Offset must be 0-10000 and limit must be 1-200");
        }
        if (!secureDirectoryStreams) {
            return pythonFileOperation("read", path, Integer.toString(start), Integer.toString(count));
        }
        return inParent(path, (parent, name) -> {
            requireRegularFile(parent, name);
            try (SeekableByteChannel channel = parent.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() > MAX_READ_BYTES) throw new IllegalArgumentException("File exceeds 1 MiB read limit");
                byte[] bytes = Channels.newInputStream(channel).readNBytes(MAX_READ_BYTES + 1);
                if (bytes.length > MAX_READ_BYTES) throw new IllegalArgumentException("File exceeds 1 MiB read limit");
                String text = decode(bytes);
                StringBuilder result = new StringBuilder();
                int outputBytes = 0;
                int emitted = 0;
                int cursor = 0;
                for (int lineNumber = 0; lineNumber < start + count && cursor <= text.length(); lineNumber++) {
                    int end = text.indexOf('\n', cursor);
                    if (end < 0) end = text.length();
                    if (lineNumber >= start) {
                        int trimmedEnd = end > cursor && text.charAt(end - 1) == '\r' ? end - 1 : end;
                        String line = text.substring(cursor, trimmedEnd);
                        int additionalBytes = line.getBytes(StandardCharsets.UTF_8).length + (emitted == 0 ? 0 : 1);
                        if (outputBytes + additionalBytes > MAX_OUTPUT_BYTES - 32) {
                            result.append("\n[output truncated]");
                            break;
                        }
                        if (emitted++ > 0) result.append('\n');
                        result.append(line);
                        outputBytes += additionalBytes;
                    }
                    if (end == text.length()) break;
                    cursor = end + 1;
                }
                return result.toString();
            }
        });
    }

    @Tool(name = "write_file", description = "Create a new UTF-8 file in an existing workspace directory; refuses to overwrite files. Content is limited to 256 KiB.")
    public String writeFile(@ToolParam(description = "Workspace-relative new file path") String path,
            @ToolParam(description = "UTF-8 file content (at most 256 KiB)") String content) {
        byte[] bytes = encode(content);
        if (!secureDirectoryStreams) return pythonFileOperation("write", path, content);
        return inParent(path, (parent, name) -> {
            try (SeekableByteChannel channel = parent.newByteChannel(name,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))) {
                writeAll(channel, bytes);
            } catch (FileAlreadyExistsException ex) {
                throw new IllegalArgumentException("File already exists");
            }
            return "Created " + path;
        });
    }

    @Tool(name = "edit_file", description = "Replace one unique occurrence of oldString in a workspace UTF-8 file, or all occurrences with replaceAll=true. Maximum resulting file size: 256 KiB.")
    public String editFile(@ToolParam(description = "Workspace-relative file path") String path,
            @ToolParam(description = "Non-empty original text") String oldString,
            @ToolParam(description = "Replacement text") String newString,
            @ToolParam(description = "Replace all occurrences instead of requiring exactly one", required = false) Boolean replaceAll) {
        if (oldString == null || oldString.isEmpty() || newString == null
                || oldString.length() > MAX_WRITE_BYTES || newString.length() > MAX_WRITE_BYTES) {
            throw new IllegalArgumentException("Original and replacement text must be at most 256 KiB; original text must not be empty");
        }
        if (!secureDirectoryStreams) {
            return pythonFileOperation("edit", path, oldString, newString, Boolean.TRUE.equals(replaceAll) ? "1" : "0");
        }
        return inParent(path, (parent, name) -> {
            requireRegularFile(parent, name);
            try (SeekableByteChannel channel = parent.newByteChannel(name,
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() > MAX_WRITE_BYTES) throw new IllegalArgumentException("File exceeds 256 KiB edit limit");
                byte[] originalBytes = Channels.newInputStream(channel).readNBytes(MAX_WRITE_BYTES + 1);
                if (originalBytes.length > MAX_WRITE_BYTES) throw new IllegalArgumentException("File exceeds 256 KiB edit limit");
                String original = decode(originalBytes);
                int first = original.indexOf(oldString);
                if (first < 0) throw new IllegalArgumentException("Original text not found");
                int occurrences = 1;
                int next = first + oldString.length();
                while ((next = original.indexOf(oldString, next)) >= 0) {
                    if (!Boolean.TRUE.equals(replaceAll)) {
                        throw new IllegalArgumentException("Original text occurs more than once; use replaceAll");
                    }
                    occurrences++;
                    next += oldString.length();
                }
                long resultSize = originalBytes.length
                        + (long) occurrences * (encode(newString).length - encode(oldString).length);
                if (resultSize > MAX_WRITE_BYTES) throw new IllegalArgumentException("Edited content exceeds 256 KiB limit");
                byte[] edited = encode(Boolean.TRUE.equals(replaceAll)
                        ? original.replace(oldString, newString)
                        : original.substring(0, first) + newString + original.substring(first + oldString.length()));
                channel.position(0);
                writeAll(channel, edited);
                channel.truncate(edited.length);
                return "Edited " + path;
            }
        });
    }

    @Tool(name = "list_files", description = "List up to 100 direct entries of a workspace-relative directory; symbolic links are not shown.")
    public String listFiles(@ToolParam(description = "Workspace-relative directory path; use . for workspace root") String path) {
        if (!secureDirectoryStreams) return pythonFileOperation("list", path);
        return inDirectory(path, directory -> {
            List<String> entries = new ArrayList<>();
            int scanned = 0;
            for (Path entry : directory) {
                if (++scanned > MAX_SCANNED_ENTRIES) throw new IllegalArgumentException("Directory has too many entries");
                Path name = entry.getFileName();
                BasicFileAttributes attributes = attributes(directory, name);
                if (!attributes.isSymbolicLink()) entries.add(name + (attributes.isDirectory() ? "/" : ""));
            }
            entries.sort(Comparator.naturalOrder());
            return boundedEntries(entries);
        });
    }

    @Tool(name = "glob_files", description = "Find matching workspace-relative files using a Java glob (for example **/*.txt); searches at most 8 levels and 2000 entries; symbolic links are skipped.")
    public String globFiles(@ToolParam(description = "Workspace-relative glob pattern") String pattern) {
        Path glob = relativePath(pattern, false);
        final PathMatcher matcher;
        try {
            matcher = root.getFileSystem().getPathMatcher("glob:" + glob);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid glob pattern");
        }
        if (!secureDirectoryStreams) {
            String[] candidates = pythonFileOperation("glob", ".").split("\\x00");
            List<String> matches = new ArrayList<>();
            for (String candidate : candidates) {
                if (!candidate.isEmpty() && matcher.matches(Path.of(candidate))) matches.add(candidate);
            }
            matches.sort(Comparator.naturalOrder());
            return boundedEntries(matches);
        }
        return inDirectory(".", directory -> {
            List<String> matches = new ArrayList<>();
            int[] scanned = {0};
            collectMatches(directory, Path.of(""), matcher, matches, scanned, 0);
            matches.sort(Comparator.naturalOrder());
            return boundedEntries(matches);
        });
    }

    private static String boundedEntries(List<String> entries) {
        StringBuilder result = new StringBuilder();
        int emitted = 0;
        int outputBytes = 0;
        for (String entry : entries) {
            int bytes = entry.getBytes(StandardCharsets.UTF_8).length + 1;
            if (emitted >= MAX_ENTRIES || outputBytes + bytes > MAX_OUTPUT_BYTES - 32) {
                result.append("[results truncated]");
                return result.toString();
            }
            result.append(entry).append('\n');
            outputBytes += bytes;
            emitted++;
        }
        return result.toString();
    }

    private static void collectMatches(SecureDirectoryStream<Path> directory, Path prefix, PathMatcher matcher,
            List<String> matches, int[] scanned, int depth) throws IOException {
        for (Path entry : directory) {
            if (++scanned[0] > MAX_SCANNED_ENTRIES) throw new IllegalArgumentException("Glob scanned too many entries");
            Path name = entry.getFileName();
            BasicFileAttributes attributes = attributes(directory, name);
            if (attributes.isSymbolicLink()) continue;
            Path relative = prefix.resolve(name);
            if (attributes.isRegularFile() && matcher.matches(relative)) matches.add(relative.toString());
            if (attributes.isDirectory() && depth + 1 < MAX_DEPTH) {
                try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    collectMatches(child, relative, matcher, matches, scanned, depth + 1);
                }
            }
        }
    }

    private static void requireRegularFile(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        if (!attributes(parent, name).isRegularFile()) throw new IllegalArgumentException("File is not a regular file or is a symbolic link");
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        BasicFileAttributeView view = directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        return view.readAttributes();
    }

    private Path relativePath(String input, boolean allowRoot) {
        if (input == null || input.isBlank() || input.length() > 512) throw new IllegalArgumentException("Invalid workspace-relative path");
        try {
            Path relative = Path.of(input);
            if (relative.isAbsolute() || (!allowRoot && relative.getNameCount() == 0)) {
                throw new IllegalArgumentException("Absolute or empty workspace paths are not allowed");
            }
            for (Path component : relative) {
                if (component.toString().equals("..")) throw new IllegalArgumentException("Parent traversal is not allowed");
            }
            if (!allowRoot && relative.normalize().toString().isEmpty()) {
                throw new IllegalArgumentException("Expected a workspace file path");
            }
            return relative.normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Invalid workspace-relative path");
        }
    }

    /** Reads raw bytes for workspace searches without following links at any workspace path component. */
    byte[] readBytes(String relative, int maxBytes) {
        if (maxBytes < 1 || maxBytes > MAX_READ_BYTES) {
            throw new IllegalArgumentException("Read limit must be 1 byte to 1 MiB");
        }
        if (!secureDirectoryStreams) return pythonOperation("bytes", relative, Integer.toString(maxBytes));
        return inParent(relative, (parent, name) -> {
            requireRegularFile(parent, name);
            try (SeekableByteChannel channel = parent.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                if (channel.size() > maxBytes) throw new IllegalArgumentException("File exceeds read limit");
                byte[] bytes = Channels.newInputStream(channel).readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) throw new IllegalArgumentException("File exceeds read limit");
                return bytes;
            }
        });
    }

    /** Returns sorted workspace-relative regular file paths from a bounded, no-follow traversal. */
    List<String> regularFiles(String relativeRoot, int maxEntries, int maxDepth) {
        if (maxEntries < 1 || maxEntries > MAX_SCANNED_ENTRIES || maxDepth < 1 || maxDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("Directory limits must be 1-2000 entries and 1-8 levels");
        }
        Path start = relativePath(relativeRoot, true);
        if (!secureDirectoryStreams) {
            String names = decode(pythonOperation("files", relativeRoot,
                    Integer.toString(maxEntries), Integer.toString(maxDepth)));
            if (names.isEmpty()) return List.of();
            String prefix = start.toString().isEmpty() ? "" : start + "/";
            List<String> paths = new ArrayList<>();
            for (String name : names.split("\\x00")) paths.add(prefix + name);
            return paths;
        }
        return inDirectory(relativeRoot, directory -> {
            List<String> paths = new ArrayList<>();
            int[] scanned = {0};
            collectFiles(directory, start, paths, scanned, 0, maxEntries, maxDepth);
            paths.sort(Comparator.naturalOrder());
            return paths;
        });
    }

    private static void collectFiles(SecureDirectoryStream<Path> directory, Path prefix,
            List<String> result, int[] scanned, int depth, int maxEntries, int maxDepth) throws IOException {
        for (Path entry : directory) {
            if (++scanned[0] > MAX_SCANNED_ENTRIES || result.size() > maxEntries) {
                throw new IllegalArgumentException("Directory has too many entries");
            }
            Path name = entry.getFileName();
            BasicFileAttributes attributes = attributes(directory, name);
            if (attributes.isSymbolicLink()) continue;
            Path relative = prefix.resolve(name);
            if (attributes.isRegularFile()) result.add(relative.toString());
            if (attributes.isDirectory() && depth + 1 < maxDepth) {
                try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                    collectFiles(child, relative, result, scanned, depth + 1, maxEntries, maxDepth);
                }
            }
        }
        if (result.size() > maxEntries) throw new IllegalArgumentException("Directory has too many entries");
    }

    private String pythonFileOperation(String mode, String path, String... arguments) {
        return decode(pythonOperation(mode, path, arguments));
    }

    private byte[] pythonOperation(String mode, String path, String... arguments) {
        boolean directory = mode.equals("list") || mode.equals("glob") || mode.equals("files");
        Path relative = relativePath(path, directory);
        if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("Workspace root cannot be a symbolic link");
        int maxOutput = mode.equals("glob") || mode.equals("files") ? MAX_BRIDGE_BYTES
                : mode.equals("bytes") ? MAX_READ_BYTES + 1 : MAX_OUTPUT_BYTES + 1024;
        Process process;
        try {
            process = new ProcessBuilder("/usr/bin/python3", "-I", "-c", PYTHON_BRIDGE, root.toString(), mode)
                    .redirectErrorStream(true).start();
        } catch (IOException ex) {
            throw new IllegalStateException("Secure workspace operations require Python 3 on this filesystem");
        }
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try (InputStream input = process.getInputStream()) {
                return input.readNBytes(maxOutput + 2);
            } catch (IOException ex) {
                throw new IllegalStateException("Workspace file operation failed");
            }
        });
        try {
            try (DataOutputStream input = new DataOutputStream(process.getOutputStream())) {
                writeArgument(input, relative.toString().isEmpty() ? "." : relative.toString());
                for (String argument : arguments) writeArgument(input, argument);
            }
            byte[] response = output.get(10, TimeUnit.SECONDS);
            if (response.length == 0 || response.length > maxOutput + 1 || !process.waitFor(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Workspace operation failed or exceeded output limit");
            }
            if (response[0] == '1') {
                throw new IllegalArgumentException(new String(response, 1, response.length - 1, StandardCharsets.UTF_8));
            }
            if (response[0] != '0' || process.exitValue() != 0) {
                throw new IllegalStateException("Workspace file operation failed");
            }
            return java.util.Arrays.copyOfRange(response, 1, response.length);
        } catch (IOException | ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Workspace file operation failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workspace operation interrupted");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void writeArgument(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private <T> T inParent(String input, FileOperation<T> operation) {
        Path path = relativePath(input, false);
        return inDirectory(path.getParent() == null ? "." : path.getParent().toString(),
                directory -> operation.apply(directory, path.getFileName()));
    }

    private <T> T inDirectory(String input, DirectoryOperation<T> operation) {
        Path relative = relativePath(input, true);
        if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("Workspace root cannot be a symbolic link");
        try (DirectoryStream<Path> parentStream = Files.newDirectoryStream(root.getParent() == null ? root : root.getParent())) {
            if (!(parentStream instanceof SecureDirectoryStream<Path> parent)) {
                throw new IllegalStateException("Secure workspace access is not supported by this filesystem");
            }
            if (root.getParent() == null) return descend(parent, relative, 0, operation);
            try (SecureDirectoryStream<Path> secure = parent.newDirectoryStream(root.getFileName(), LinkOption.NOFOLLOW_LINKS)) {
                return descend(secure, relative, 0, operation);
            }
        } catch (NoSuchFileException ex) {
            throw new IllegalArgumentException("Workspace file or directory does not exist");
        } catch (IOException ex) {
            throw new IllegalStateException("Workspace file operation failed");
        }
    }

    private static <T> T descend(SecureDirectoryStream<Path> directory, Path path, int index,
            DirectoryOperation<T> operation) throws IOException {
        if (path.toString().isEmpty() || index >= path.getNameCount()) return operation.apply(directory);
        try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(path.getName(index), LinkOption.NOFOLLOW_LINKS)) {
            return descend(child, path, index + 1, operation);
        }
    }

    private static byte[] encode(String content) {
        if (content == null) throw new IllegalArgumentException("File content is required");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) throw new IllegalArgumentException("Content exceeds 256 KiB limit");
        return bytes;
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("File must contain valid UTF-8 text");
        }
    }

    private static void writeAll(SeekableByteChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    @FunctionalInterface
    private interface DirectoryOperation<T> { T apply(SecureDirectoryStream<Path> directory) throws IOException; }

    @FunctionalInterface
    private interface FileOperation<T> { T apply(SecureDirectoryStream<Path> directory, Path name) throws IOException; }
}
