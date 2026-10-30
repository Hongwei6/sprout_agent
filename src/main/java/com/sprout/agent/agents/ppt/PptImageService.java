package com.sprout.agent.agents.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sprout.agent.config.MinioConfiguration.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PptImageService {
    private static final Logger log = LoggerFactory.getLogger(PptImageService.class);
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final String OBJECT_PREFIX = "ppt/images/";

    private static final int MAX_INSPECTION_ATTEMPTS = 3;
    private final ObjectProvider<ImageModel> imageModels;
    private final ChatModel chatModel;
    private final MinioClient minio;
    private final MinioProperties minioProperties;
    private final String publicBaseUrl;

    public PptImageService(ObjectProvider<ImageModel> imageModels, ChatModel chatModel, MinioClient minio,
            MinioProperties minioProperties, @Value("${sprout.ppt.image.public-base-url:}") String publicBaseUrl) {
        this.imageModels = imageModels;
        this.chatModel = chatModel;
        this.minio = minio;
        this.minioProperties = minioProperties;
        this.publicBaseUrl = publicBaseUrl;
    }

    /** Replaces only empty image/background URLs; a failure leaves schema persistence to the caller. */
    public void fillMissingImages(ObjectNode schema, String conversationId) {
        fillMissingImages(schema, conversationId, ignored -> {});
    }

    /** Persists each successfully uploaded URL before generating the next image. */
    public void fillMissingImages(ObjectNode schema, String conversationId, Consumer<ObjectNode> afterEachImage) {
        fillMissingImages(schema, conversationId, afterEachImage, ignored -> {});
    }

    public void fillMissingImages(ObjectNode schema, String conversationId,
            Consumer<ObjectNode> afterEachImage, Consumer<String> progress) {
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(afterEachImage, "afterEachImage");
        if (conversationId == null || !conversationId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("无效的 PPT 会话 ID");
        }
        JsonNode slides = schema.path("slides");
        if (!slides.isArray()) throw new IllegalArgumentException("PPT Schema 缺少 slides 数组");
        int total = 0;
        for (JsonNode slide : slides) {
            for (var fields = slide.path("data").fields(); fields.hasNext();) {
                JsonNode field = fields.next().getValue();
                if (("image".equals(field.path("type").asText()) || "background".equals(field.path("type").asText()))
                        && field.path("url").asText("").isBlank()) total++;
            }
        }
        int current = 0;
        for (JsonNode slide : slides) {
            JsonNode data = slide.path("data");
            if (!data.isObject()) throw new IllegalArgumentException("PPT 页面缺少 data 对象");
            var fields = data.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode field = entry.getValue();
                String type = field.path("type").asText();
                if (!"image".equals(type) && !"background".equals(type)) continue;
                if (!field.isObject()) throw new IllegalArgumentException("PPT 图片字段必须是对象");
                String url = field.path("url").asText("");
                if (!url.isBlank()) continue;
                String prompt = field.path("content").asText("");
                if (prompt.isBlank()) throw new IllegalArgumentException("PPT 图片缺少生成提示词：" + entry.getKey());
                String label = "第 " + (++current) + "/" + total + " 张配图";
                try {
                    ((ObjectNode) field).put("url", generateAndStore(prompt, conversationId,
                            stage -> progress.accept(label + "：" + stage)));
                } catch (IllegalStateException ex) {
                    throw new IllegalStateException(label + "生成失败：" + ex.getMessage(), ex);
                }
                afterEachImage.accept(schema);
                progress.accept(label + "：已保存");
            }
        }
    }

    public StoredImage readImage(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("无效的图片 ID");
        try (var stream = minio.getObject(GetObjectArgs.builder().bucket(minioProperties.bucket())
                .object(OBJECT_PREFIX + id).build())) {
            byte[] bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1);
            return new StoredImage(bytes, contentType(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取 PPT 图片", ex);
        }
    }

    private String generateAndStore(String prompt, String conversationId, Consumer<String> progress) {
        ImageModel model = imageModels.getIfAvailable();
        if (model == null) throw new IllegalStateException("未配置可用的文生图模型，无法生成 PPT 图片");
        String base = publicBaseUrl.strip();
        URI publicUri;
        try {
            publicUri = URI.create(base);
            if (!("http".equals(publicUri.getScheme()) || "https".equals(publicUri.getScheme()))
                    || publicUri.getHost() == null || publicUri.getUserInfo() != null
                    || publicUri.getQuery() != null || publicUri.getFragment() != null
                    || publicUri.getRawPath() != null && !publicUri.getRawPath().isEmpty()
                            && !"/".equals(publicUri.getRawPath())) {
                throw new IllegalArgumentException("invalid URL");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("未配置有效的 sprout.ppt.image.public-base-url，无法生成可访问图片链接", ex);
        }
        ImageResponse response = null;
        String rejectedPrompt = null;
        for (int attempt = 1; attempt <= MAX_INSPECTION_ATTEMPTS; attempt++) {
            progress.accept("正在优化配图描述（第 " + attempt + "/" + MAX_INSPECTION_ATTEMPTS + " 次）");
            String imagePrompt = rewriteImagePrompt(prompt, rejectedPrompt);
            log.info("PPT 配图提示词（会话 {}，第 {}/{} 次）：{}", conversationId, attempt,
                    MAX_INSPECTION_ATTEMPTS, imagePrompt);
            progress.accept("正在调用文生图模型（第 " + attempt + "/" + MAX_INSPECTION_ATTEMPTS + " 次）");
            try {
                response = model.call(new ImagePrompt(imagePrompt));
                break;
            } catch (Exception ex) {
                if (!isInspectionFailure(ex)) {
                    throw new IllegalStateException("文生图模型调用失败：" + ex.getClass().getSimpleName(), ex);
                }
                if (attempt == MAX_INSPECTION_ATTEMPTS) {
                    throw new IllegalStateException("配图连续 3 次未通过文生图服务商的安全审核；请调整 PPT 主题或配图要求", ex);
                }
                rejectedPrompt = imagePrompt;
                progress.accept("配图未通过审核，正在更换画面描述后重试");
            }
        }
        byte[] bytes;
        try {
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new IllegalStateException("文生图模型未返回图片");
            }
            Image image = response.getResult().getOutput();
            if (image.getB64Json() != null && !image.getB64Json().isBlank()) {
                String b64 = image.getB64Json();
                if (b64.length() > (MAX_IMAGE_BYTES + 2) / 3 * 4 + 4) {
                    throw new IllegalStateException("生成图片超过 10 MiB");
                }
                bytes = Base64.getDecoder().decode(b64);
            } else if (image.getUrl() != null && !image.getUrl().isBlank()) {
                bytes = download(image.getUrl());
            } else {
                throw new IllegalStateException("文生图模型未返回图片数据或下载地址");
            }
            progress.accept("图片已生成，正在上传到 MinIO");
            String mediaType = contentType(bytes).toString();
            String id = UUID.randomUUID().toString().replace("-", "");
            ensureBucket();
            minio.putObject(PutObjectArgs.builder().bucket(minioProperties.bucket())
                    .object(OBJECT_PREFIX + id).stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(mediaType).build());
            return base.replaceAll("/+$", "") + "/api/ppt/images/" + id;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("PPT 图片生成或存储失败（会话 " + conversationId + "）", ex);
        }
    }

    private String rewriteImagePrompt(String prompt, String rejectedPrompt) {
        try {
            String rewritten = chatModel.call(new Prompt("""
                    请将下面的 PPT 配图需求改写成一条适合公开演示文稿的中文文生图描述。
                    保留主题、视觉风格和主要构图；只描述画面，不要生成文字、水印或人物肖像。
                    如果包含不适合公开展示的敏感或危险内容，改为安全的抽象图形或中性场景，
                    不要通过同义替换来保留不合适的画面。只输出改写后的描述，不要解释。

                    原始需求：
                    %s
                    %s
                    """.formatted(prompt, rejectedPrompt == null ? "" : """
                    上一次的画面描述未通过服务商审核，请换一种明显不同、适合公开展示的画面：
                    """ + rejectedPrompt))).getResult().getOutput().getText();
            if (rewritten == null || rewritten.isBlank()) throw new IllegalStateException("改写结果为空");
            return rewritten.strip();
        } catch (Exception ex) {
            throw new IllegalStateException("配图描述优化失败，未调用文生图模型", ex);
        }
    }

    private static boolean isInspectionFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("DataInspectionFailed")) return true;
        }
        return false;
    }

    private byte[] download(String imageUrl) throws IOException {
        URI uri;
        try {
            uri = URI.create(imageUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid image URL");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("private image URL");
                }
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("文生图模型返回的图片地址无效", ex);
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("图片下载失败，HTTP " + status);
            if (connection.getContentLengthLong() > MAX_IMAGE_BYTES) throw new IOException("生成图片超过 10 MiB");
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(MAX_IMAGE_BYTES + 1);
                if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("生成图片超过 10 MiB");
                return bytes;
            }
        } finally {
            connection.disconnect();
        }
    }

    private void ensureBucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(minioProperties.bucket()).build())) {
            try {
                minio.makeBucket(MakeBucketArgs.builder().bucket(minioProperties.bucket()).build());
            } catch (Exception ex) {
                if (!minio.bucketExists(BucketExistsArgs.builder().bucket(minioProperties.bucket()).build())) throw ex;
            }
        }
    }

    private static MediaType contentType(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IOException("图片为空或超过 10 MiB");
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) {
            return MediaType.IMAGE_PNG;
        }
        if (bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                && (bytes[2] & 255) == 255) return MediaType.IMAGE_JPEG;
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B'
                && bytes[11] == 'P') return MediaType.parseMediaType("image/webp");
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9')
                && bytes[5] == 'a') return MediaType.IMAGE_GIF;
        throw new IOException("文生图模型返回的文件不是支持的图片格式");
    }

    public record StoredImage(byte[] bytes, MediaType contentType) {
    }
}
