package com.sprout.agent.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.sprout.agent.config.MinioConfiguration.MinioProperties;
import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.mapper.UploadedFileMapper;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import okhttp3.Headers;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class UploadedFileServiceTest {

    @Test
    void persistsUtf8TextAndMetadata() throws Exception {
        UploadedFileMapper mapper = mock(UploadedFileMapper.class);
        MinioClient minio = mock(MinioClient.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        UploadedFileService files = new UploadedFileService(mapper, minio,
                new MinioProperties("http://localhost:9000", "access", "secret", "files"));

        var response = files.upload(new MockMultipartFile("file", "note.txt", "text/plain",
                "附件原文".getBytes(StandardCharsets.UTF_8)));
        ArgumentCaptor<UploadedFileEntity> captured = ArgumentCaptor.forClass(UploadedFileEntity.class);
        verify(mapper).insert(captured.capture());
        verify(minio).putObject(any(PutObjectArgs.class));
        UploadedFileEntity saved = captured.getValue();
        assertEquals(response.fileId(), saved.getId());
        assertEquals("附件原文", saved.getExtractedText());
    }

    @Test
    void rejectsUnsupportedAndUnparseableFilesBeforeWriting() {
        UploadedFileService files = new UploadedFileService(mock(UploadedFileMapper.class), mock(MinioClient.class),
                new MinioProperties("http://localhost:9000", "access", "secret", "files"));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> files.upload(new MockMultipartFile("file", "bad.exe", "application/octet-stream",
                        new byte[]{1, 2, 3}))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> files.upload(new MockMultipartFile("file", "bad.pdf", "application/pdf",
                        "not a pdf".getBytes(StandardCharsets.UTF_8)))).getStatusCode());
    }

    @Test
    void readsImageBytesFromMinio() throws Exception {
        UploadedFileMapper mapper = mock(UploadedFileMapper.class);
        MinioClient minio = mock(MinioClient.class);
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1};
        UploadedFileEntity image = new UploadedFileEntity();
        image.setId("12345678901234567890123456789012");
        image.setName("截图.png");
        image.setContentType("image/png");
        image.setSizeBytes(png.length);
        when(mapper.selectById(image.getId())).thenReturn(image);
        when(minio.getObject(any(GetObjectArgs.class))).thenReturn(new GetObjectResponse(
                new Headers.Builder().build(), "files", null, image.getId(), new ByteArrayInputStream(png)));
        UploadedFileService files = new UploadedFileService(mapper, minio,
                new MinioProperties("http://localhost:9000", "access", "secret", "files"));

        assertArrayEquals(png, files.readBytes(files.requireFile(image.getId())));
    }
    @Test
    void recognizesImageOnceAndReusesPersistedDescriptionAfterRestart() {
        UploadedFileMapper mapper = mock(UploadedFileMapper.class);
        UploadedFileEntity image = new UploadedFileEntity();
        image.setId("12345678901234567890123456789012");
        when(mapper.selectById(image.getId())).thenReturn(image);
        var properties = new MinioProperties("http://localhost:9000", "access", "secret", "files");
        UploadedFileService first = new UploadedFileService(mapper, mock(MinioClient.class), properties);
        assertEquals("一株幼苗", first.imageDescription(image, () -> "一株幼苗"));
        UploadedFileService restarted = new UploadedFileService(mapper, mock(MinioClient.class), properties);
        assertEquals("一株幼苗", restarted.imageDescription(image, () -> {
            throw new AssertionError("不应再次调用视觉模型");
        }));
        verify(mapper, times(1)).updateById(image);
        assertTrue(image.getExtractedText().contains("幼苗"));
    }

}
