package com.sprout.agent.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
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
                new MinioProperties("http://localhost:9000", "access", "secret", "files"), mock(FileVectorService.class));

        var response = files.upload(new MockMultipartFile("file", "note.txt", "text/plain",
                "附件原文".getBytes(StandardCharsets.UTF_8)));
        ArgumentCaptor<UploadedFileEntity> captured = ArgumentCaptor.forClass(UploadedFileEntity.class);
        verify(mapper).insert(captured.capture());
        verify(minio).putObject(any(PutObjectArgs.class));
        UploadedFileEntity saved = captured.getValue();
        assertEquals(response.fileId(), saved.getId());
        assertEquals("附件原文", saved.getExtractedText());
        assertTrue(!saved.isIndexed());
    }

    @Test
    void indexesOnlyTextAboveDirectContextLimitWithoutTruncating() throws Exception {
        UploadedFileMapper mapper = mock(UploadedFileMapper.class);
        MinioClient minio = mock(MinioClient.class);
        FileVectorService vectors = mock(FileVectorService.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        UploadedFileService files = new UploadedFileService(mapper, minio,
                new MinioProperties("http://localhost:9000", "access", "secret", "files"), vectors);
        String small = "x".repeat(12_000);
        String large = "x".repeat(12_001) + "结尾";
        files.upload(new MockMultipartFile("file", "small.md", "text/markdown", small.getBytes(StandardCharsets.UTF_8)));
        files.upload(new MockMultipartFile("file", "large.md", "text/markdown", large.getBytes(StandardCharsets.UTF_8)));

        ArgumentCaptor<UploadedFileEntity> captured = ArgumentCaptor.forClass(UploadedFileEntity.class);
        verify(mapper, times(2)).insert(captured.capture());
        UploadedFileEntity direct = captured.getAllValues().get(0);
        UploadedFileEntity indexed = captured.getAllValues().get(1);
        assertEquals(small, direct.getExtractedText());
        assertTrue(!direct.isIndexed());
        assertTrue(indexed.isIndexed());
        assertEquals(null, indexed.getExtractedText());
        verify(vectors).index(indexed.getId(), large);
        verify(vectors, never()).index(direct.getId(), small);
    }

    @Test
    void imagesNeverIndexEvenWhenFileIsLarge() throws Exception {
        UploadedFileMapper mapper = mock(UploadedFileMapper.class);
        MinioClient minio = mock(MinioClient.class);
        FileVectorService vectors = mock(FileVectorService.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        UploadedFileService files = new UploadedFileService(mapper, minio,
                new MinioProperties("http://localhost:9000", "access", "secret", "files"), vectors);
        byte[] png = new byte[12_100];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 0, png, 0, 8);
        files.upload(new MockMultipartFile("file", "image.png", "image/png", png));
        ArgumentCaptor<UploadedFileEntity> captured = ArgumentCaptor.forClass(UploadedFileEntity.class);
        verify(mapper).insert(captured.capture());
        assertTrue(!captured.getValue().isIndexed());
        assertEquals(null, captured.getValue().getExtractedText());
        verify(vectors, never()).index(any(), any());
    }

    @Test
    void cleansIndexedChunksAndObjectIfMetadataWriteFails() throws Exception {
        UploadedFileMapper mapper = mock(UploadedFileMapper.class);
        MinioClient minio = mock(MinioClient.class);
        FileVectorService vectors = mock(FileVectorService.class);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(mapper).insert(any());
        UploadedFileService files = new UploadedFileService(mapper, minio,
                new MinioProperties("http://localhost:9000", "access", "secret", "files"), vectors);
        assertThrows(IllegalStateException.class, () -> files.upload(new MockMultipartFile(
                "file", "large.txt", "text/plain", "a".repeat(12_001).getBytes(StandardCharsets.UTF_8))));
        ArgumentCaptor<UploadedFileEntity> captured = ArgumentCaptor.forClass(UploadedFileEntity.class);
        verify(mapper).insert(captured.capture());
        verify(vectors).delete(captured.getValue().getId());
        verify(minio).removeObject(any(io.minio.RemoveObjectArgs.class));
    }

    @Test
    void rejectsUnsupportedAndUnparseableFilesBeforeWriting() {
        UploadedFileService files = new UploadedFileService(mock(UploadedFileMapper.class), mock(MinioClient.class),
                new MinioProperties("http://localhost:9000", "access", "secret", "files"), mock(FileVectorService.class));
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
                new MinioProperties("http://localhost:9000", "access", "secret", "files"), mock(FileVectorService.class));

        assertArrayEquals(png, files.readBytes(files.requireFile(image.getId())));
    }
    @Test
    void recognizesImageOnceAndReusesPersistedDescriptionAfterRestart() {
        UploadedFileMapper mapper = mock(UploadedFileMapper.class);
        UploadedFileEntity image = new UploadedFileEntity();
        image.setId("12345678901234567890123456789012");
        when(mapper.selectById(image.getId())).thenReturn(image);
        var properties = new MinioProperties("http://localhost:9000", "access", "secret", "files");
        UploadedFileService first = new UploadedFileService(mapper, mock(MinioClient.class), properties, mock(FileVectorService.class));
        assertEquals("一株幼苗", first.imageDescription(image, () -> "一株幼苗"));
        UploadedFileService restarted = new UploadedFileService(mapper, mock(MinioClient.class), properties, mock(FileVectorService.class));
        assertEquals("一株幼苗", restarted.imageDescription(image, () -> {
            throw new AssertionError("不应再次调用视觉模型");
        }));
        verify(mapper, times(1)).updateById(image);
        assertTrue(image.getExtractedText().contains("幼苗"));
    }

}
