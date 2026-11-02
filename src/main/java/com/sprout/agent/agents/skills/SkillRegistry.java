package com.sprout.agent.agents.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Project-owned SKILL.md files: list metadata eagerly, read instructions only on demand. */
@Component
public class SkillRegistry {
    private static final int MAX_SKILL_BYTES = 128 * 1024;
    private volatile Map<String, Skill> skills;

    @Autowired
    public SkillRegistry(@Value("${sprout.agent.skills-directory:skills}") String directory) {
        this(Path.of(directory));
    }

    public SkillRegistry(Path directory) {
        this.directory = directory;
        reload();
    }

    private final Path directory;

    public synchronized void reload() {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("技能目录不存在: " + directory);
        }
        Map<String, Skill> found = new LinkedHashMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                String name = path.getFileName().toString();
                if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*") || !Files.isDirectory(path)
                        || Files.isSymbolicLink(path)) continue;
                Path file = path.resolve("SKILL.md");
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) continue;
                String content = read(file);
                String description = description(content);
                if (description.isBlank()) throw new IllegalStateException("技能缺少描述: " + name);
                found.put(name, new Skill(name, description.replaceAll("\\s+", " ").strip(), file));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法扫描技能目录: " + directory, ex);
        }
        this.skills = Collections.unmodifiableMap(found);
    }

    public List<Skill> list() {
        return new ArrayList<>(skills.values());
    }

    public String readSkill(String name) {
        Skill skill = skills.get(name);
        if (skill == null) throw new IllegalArgumentException("未知技能: " + name);
        return read(skill.file());
    }

    public synchronized Skill install(String name, String content) {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*") || content == null
                || content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SKILL_BYTES) {
            throw new IllegalArgumentException("技能名称或内容无效");
        }
        String summary = description(content);
        if (summary.isBlank()) throw new IllegalArgumentException("技能缺少描述");
        if (skills.containsKey(name)) throw new IllegalArgumentException("技能已存在: " + name);
        Path folder = directory.resolve(name);
        try {
            Files.createDirectory(folder);
            try {
                Files.writeString(folder.resolve("SKILL.md"), content);
                reload();
                return skills.get(name);
            } catch (RuntimeException | IOException ex) {
                Files.deleteIfExists(folder.resolve("SKILL.md"));
                Files.deleteIfExists(folder);
                throw ex;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法安装技能: " + name, ex);
        }
    }
    /** Publish a validated archive only after every entry has been written successfully. */
    public synchronized Skill installArchive(String name, Map<String, byte[]> files) {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*")
                || !files.containsKey("SKILL.md") || skills.containsKey(name)) {
            throw new IllegalArgumentException("技能名称无效、已存在或缺少 SKILL.md");
        }
        String content = new String(files.get("SKILL.md"), java.nio.charset.StandardCharsets.UTF_8);
        if (files.get("SKILL.md").length > MAX_SKILL_BYTES || description(content).isBlank()) {
            throw new IllegalArgumentException("SKILL.md 无效");
        }
        Path folder = directory.resolve(name);
        try {
            Files.createDirectory(folder);
            try {
                for (var entry : files.entrySet()) {
                    Path target = folder.resolve(entry.getKey()).normalize();
                    if (!target.startsWith(folder) || entry.getKey().startsWith("/")
                            || entry.getKey().contains("\\")) throw new IllegalArgumentException("ZIP 路径无效");
                    Files.createDirectories(target.getParent());
                    Files.write(target, entry.getValue(), java.nio.file.StandardOpenOption.CREATE_NEW);
                }
                reload();
                return skills.get(name);
            } catch (RuntimeException | IOException ex) {
                try (var paths = Files.walk(folder)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
                throw ex;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("无法安装技能: " + name, ex);
        }
    }


    private static String read(Path file) {
        try {
            if (Files.size(file) > MAX_SKILL_BYTES) throw new IllegalStateException("技能文件过大: " + file);
            return Files.readString(file);
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取技能文件: " + file, ex);
        }
    }

    private static String description(String content) {
        if (content.startsWith("---\n") || content.startsWith("---\r\n")) {
            int start = content.indexOf('\n') + 1;
            int end = content.indexOf("\n---", start);
            if (end < 0) throw new IllegalStateException("技能 frontmatter 未结束");
            Object yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(content.substring(start, end));
            if (yaml instanceof Map<?, ?> fields && fields.get("description") instanceof String value) {
                return value.strip();
            }
            content = content.substring(end + 4);
        }
        for (String line : content.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("#")) return line.strip();
        }
        return "";
    }

    public record Skill(String name, String description, Path file) {
    }
}
