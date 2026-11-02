package com.sprout.agent.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Runs Bash on the host as the service OS user, following the reference BashTool. */
public final class HostBashTool {
    private static final int MAX_OUTPUT = 100_000;
    private static final long TIMEOUT_SECONDS = 120;

    @Tool(name = "bash", description = "Execute a Bash command on the host OS as the service user. Prefer dedicated file tools for file operations. Commands may install software, run scripts, and access host paths.")
    public String bash(@ToolParam(description = "Bash command to execute on the host") String command,
            @ToolParam(description = "Optional absolute host working directory", required = false) String workingDirectory) {
        if (command == null || command.isBlank() || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Command must be nonempty and cannot contain NUL");
        }
        Path directory = workingDirectory == null || workingDirectory.isBlank()
                ? Path.of("").toAbsolutePath() : Path.of(workingDirectory);
        if (!directory.isAbsolute() || !Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Working directory must be an existing absolute host directory");
        }
        Process process;
        try {
            process = new ProcessBuilder("/bin/bash", "-lc", command).directory(directory.toFile())
                    .redirectErrorStream(true).start();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot launch host Bash", ex);
        }
        try {
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] bytes = process.getInputStream().readNBytes(MAX_OUTPUT + 1);
                    if (bytes.length > MAX_OUTPUT) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                    return bytes;
                } catch (IOException ex) {
                    throw new IllegalStateException("Cannot read Bash output", ex);
                }
            });
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Host command exceeded 120 seconds");
            }
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            if (bytes.length > MAX_OUTPUT) throw new IllegalStateException("Host command output exceeds 100 KB");
            return "Exit code " + process.exitValue() + ":\n" + new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Host command interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Cannot finish reading host command output", ex);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
