package com.sprout.agent.tool;

import com.sprout.agent.agents.skills.SkillRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Only Python scripts from installed skills run, inside a locked-down Docker container. */
public final class RunSkillScriptTool {
    private static final int MAX_OUTPUT = 16 * 1024;
    private final SkillRegistry registry;
    private final Launcher launcher;

    public RunSkillScriptTool(SkillRegistry registry) {
        this(registry, RunSkillScriptTool::start);
    }

    RunSkillScriptTool(SkillRegistry registry, Launcher launcher) {
        this.registry = registry;
        this.launcher = launcher;
    }

    @Tool(name = "run_skill_script", description = "在无网络、只读且有资源限制的 Docker 容器运行已安装技能的现有 .py 脚本。只支持 Python；不会执行宿主机命令。")
    public String run(@ToolParam(description = "已安装技能名称") String skill,
            @ToolParam(description = "技能目录下相对路径，必须以 .py 结尾，例如 scripts/check.py") String script,
            @ToolParam(description = "传给脚本的纯文本参数；无需参数则为空字符串") String argument) {
        if (skill == null || !skill.matches("[A-Za-z0-9][A-Za-z0-9_-]*") || script == null
                || !script.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\.py")
                || script.length() > 256 || argument == null || argument.length() > 2048) {
            throw new IllegalArgumentException("技能、Python 脚本路径或参数无效");
        }
        for (String part : script.split("/")) {
            if (part.equals(".") || part.equals("..")) throw new IllegalArgumentException("脚本路径无效");
        }
        Path root = registry.list().stream().filter(s -> s.name().equals(skill))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("未知技能: " + skill))
                .file().getParent();
        Path file = root.resolve(script);
        if (Files.isSymbolicLink(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("脚本不存在或不是常规文件");
        }
        Path part = root;
        for (String segment : script.split("/")) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part)) throw new IllegalArgumentException("脚本路径包含符号链接");
        }
        try {
            Path mount = root.toRealPath();
            if (!file.toRealPath().startsWith(mount)) throw new IllegalArgumentException("脚本超出技能目录");
            List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "--pull", "never",
                    "--network", "none", "--read-only", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--pids-limit", "32",
                    "--memory", "128m", "--memory-swap", "128m", "--cpus", "0.5", "--user", "65534:65534",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m", "--env", "HOME=/tmp",
                    "--env", "PYTHONDONTWRITEBYTECODE=1",
                    "--mount", "type=bind,src=" + mount + ",dst=/skill,readonly",
                    "--workdir", "/skill", "python:3.12-alpine", "timeout", "-s", "KILL", "12",
                    "python", "-I", "-B", "/skill/" + script));
            if (!argument.isEmpty()) command.add(argument);
            Process process = launcher.start(command);
            try {
                var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        byte[] bytes = process.getInputStream().readNBytes(MAX_OUTPUT + 1);
                        if (bytes.length > MAX_OUTPUT) process.destroyForcibly();
                        return bytes;
                    } catch (IOException ex) {
                        throw new IllegalStateException("无法读取脚本输出", ex);
                    }
                });
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("脚本执行超过 15 秒");
                }
                byte[] bytes = output.get(2, TimeUnit.SECONDS);
                if (bytes.length > MAX_OUTPUT) throw new IllegalStateException("脚本输出超过 16 KiB");
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                return "退出码 " + process.exitValue() + "：\n" + text;
            } catch (InterruptedException ex) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("脚本执行被中断", ex);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
                process.destroyForcibly();
                throw new IllegalStateException("脚本输出读取失败", ex);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法启动隔离容器；请检查 Docker 和本地 python:3.12-alpine 镜像", ex);
        }
    }

    private static Process start(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", "/usr/local/bin:/usr/bin:/bin");
        return builder.start();
    }

    @FunctionalInterface interface Launcher { Process start(List<String> command) throws IOException; }
}
