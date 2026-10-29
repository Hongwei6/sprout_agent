package com.sprout.agent.service;

import com.sprout.agent.config.MinioConfiguration.MinioProperties;
import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.mapper.UploadedFileMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UploadedFileService {

    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_CONTEXT_CHARS = 100_000;

    private final UploadedFileMapper mapper;
    private final MinioClient minio;
    private final MinioProperties properties;

    public UploadedFileService(UploadedFileMapper mapper, MinioClient minio, MinioProperties properties) {
        this.mapper = mapper;
        this.minio = minio;
        this.properties = properties;
    }

    public UploadResponse upload(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件必须非空且不超过 10 MiB");
        }
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名不能为空");
        }
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isBlank() || name.length() > 255 || name.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名无效或超过 255 个字符");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件必须非空且不超过 10 MiB");
            }
            Parsed parsed = parse(name, bytes);
            String id = UUID.randomUUID().toString().replace("-", "");
            ensureBucket();
            minio.putObject(PutObjectArgs.builder().bucket(properties.bucket()).object(id)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1).contentType(parsed.contentType()).build());
            UploadedFileEntity row = new UploadedFileEntity();
            row.setId(id);
            row.setName(name);
            row.setContentType(parsed.contentType());
            row.setSizeBytes(bytes.length);
            row.setExtractedText(parsed.text());
            row.setCreatedAt(LocalDateTime.now());
            try {
                mapper.insert(row);
            } catch (RuntimeException ex) {
                try {
                    minio.removeObject(RemoveObjectArgs.builder().bucket(properties.bucket()).object(id).build());
                } catch (Exception cleanupFailure) {
                    ex.addSuppressed(cleanupFailure);
                }
                throw ex;
            }
            return new UploadResponse(id, name, parsed.contentType(), bytes.length);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取上传文件", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("上传文件存储失败", ex);
        }
    }

    public UploadedFileEntity requireFile(String fileId) {
        UploadedFileEntity file = mapper.selectById(fileId);
        if (file == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        return file;
    }
    public synchronized String imageDescription(UploadedFileEntity file, java.util.function.Supplier<String> recognize) {
        UploadedFileEntity stored = requireFile(file.getId());
        if (stored.getExtractedText() != null && !stored.getExtractedText().isBlank()) {
            return stored.getExtractedText();
        }
        String description = recognize.get();
        if (description == null || description.isBlank()) {
            throw new IllegalStateException("图片理解模型没有返回描述");
        }
        stored.setExtractedText(description);
        mapper.updateById(stored);
        return description;
    }

    public byte[] readBytes(UploadedFileEntity file) {
        try (var stream = minio.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket()).object(file.getId()).build())) {
            byte[] bytes = stream.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length != file.getSizeBytes()) throw new IOException("附件长度与记录不一致");
            return bytes;
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取附件文件", ex);
        }
    }

    private void ensureBucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())) {
            try {
                minio.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
            } catch (Exception ex) {
                if (!minio.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build())) throw ex;
            }
        }
    }

    private Parsed parse(String name, byte[] bytes) throws IOException {
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try {
            String text;
            String contentType;
            switch (extension) {
                case "txt", "md" -> {
                    contentType = "txt".equals(extension) ? "text/plain" : "text/markdown";
                    text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                }
                case "pdf" -> {
                    if (!startsWith(bytes, "%PDF".getBytes(StandardCharsets.US_ASCII))) throw new IOException("PDF 文件头无效");
                    contentType = "application/pdf";
                    try (PDDocument document = PDDocument.load(bytes)) {
                        text = new PDFTextStripper().getText(document);
                    }
                }
                case "docx" -> {
                    if (!startsWith(bytes, new byte[]{'P', 'K'})) throw new IOException("DOCX 文件头无效");
                    contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
                            XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                        text = extractor.getText();
                    }
                }
                case "png" -> {
                    if (!startsWith(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) throw new IOException("PNG 文件头无效");
                    return new Parsed("image/png", null);
                }
                case "jpg", "jpeg" -> {
                    if (bytes.length < 4 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216
                            || (bytes[2] & 255) != 255) throw new IOException("JPEG 文件头无效");
                    return new Parsed("image/jpeg", null);
                }
                case "webp" -> {
                    if (bytes.length < 12 || !startsWith(bytes, new byte[]{'R', 'I', 'F', 'F'})
                            || !"WEBP".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII))) {
                        throw new IOException("WebP 文件头无效");
                    }
                    return new Parsed("image/webp", null);
                }
                case "gif" -> {
                    if (!startsWith(bytes, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                            && !startsWith(bytes, "GIF89a".getBytes(StandardCharsets.US_ASCII))) throw new IOException("GIF 文件头无效");
                    return new Parsed("image/gif", null);
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "支持 PDF、DOCX、TXT、MD、PNG、JPEG、WebP、GIF 文件");
            }
            if (text.isBlank()) throw new IOException("文件没有可提取的文字");
            text = text.strip();
            if (text.length() > MAX_CONTEXT_CHARS) {
                text = text.substring(0, MAX_CONTEXT_CHARS) + "\n[附件文字过长，已截断]";
            }
            return new Parsed(contentType, text);
        } catch (CharacterCodingException ex) {
            throw new IOException("文本文件必须使用 UTF-8 编码", ex);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法解析文件：" + ex.getMessage(), ex);
        }
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private record Parsed(String contentType, String text) {
    }

    public record UploadResponse(String fileId, String name, String contentType, long size) {
    }
}
