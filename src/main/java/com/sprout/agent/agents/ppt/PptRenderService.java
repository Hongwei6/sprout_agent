package com.sprout.agent.agents.ppt;

import com.sprout.agent.config.MinioConfiguration.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class PptRenderService {
    private final MinioClient minio;
    private final MinioProperties storage;
    private final String python;

    public PptRenderService(MinioClient minio, MinioProperties storage,
            @Value("${sprout.ppt.python:.venv/bin/python3}") String python) {
        this.minio = minio;
        this.storage = storage;
        this.python = python;
    }

    public void render(AiPptInst inst) {
        Path work;
        try {
            work = Files.createTempDirectory("sprout-ppt-");
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建 PPT 渲染目录", ex);
        }
        Process process = null;
        try {
            Path template = work.resolve("template.pptx");
            Path script = work.resolve("render_ppt.py");
            Path schema = work.resolve("schema.json");
            Path output = work.resolve("result.pptx");
            copy("templates/ai.pptx", template);
            copy("python/render_ppt.py", script);
            Files.writeString(schema, inst.getPptSchema());
            ProcessBuilder builder = new ProcessBuilder(python, script.toString(), "--template", template.toString(),
                    "--output", output.toString()).redirectErrorStream(true);
            builder.environment().put("PPT_SCHEMA_FILE", schema.toString());
            process = builder.start();
            if (!process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("PPT 渲染超时");
            }
            String log = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IllegalStateException("PPT 渲染失败：" + log.substring(0, Math.min(log.length(), 500)));
            }
            try (InputStream input = Files.newInputStream(output)) {
                minio.putObject(PutObjectArgs.builder().bucket(storage.bucket())
                        .object(objectName(inst.getId())).stream(input, Files.size(output), -1)
                        .contentType("application/vnd.openxmlformats-officedocument.presentationml.presentation").build());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("PPT 文件渲染或上传失败", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PPT 渲染已中断", ex);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("PPT 文件渲染或上传失败", ex);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            try (var files = Files.list(work)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
                Files.deleteIfExists(work);
            } catch (IOException ignored) {
                // The result is already stored or the original failure is more relevant.
            }
        }
    }

    public InputStream download(long id) throws IOException {
        try {
            return minio.getObject(GetObjectArgs.builder().bucket(storage.bucket()).object(objectName(id)).build());
        } catch (Exception ex) {
            throw new IOException("无法读取 PPT 文件", ex);
        }
    }

    private static String objectName(long id) { return "ppt/files/" + id + ".pptx"; }

    private static void copy(String resource, Path target) throws IOException {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            Files.copy(input, target);
        }
    }
}
