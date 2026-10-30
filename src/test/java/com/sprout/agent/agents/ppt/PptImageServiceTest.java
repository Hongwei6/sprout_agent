package com.sprout.agent.agents.ppt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sprout.agent.config.MinioConfiguration.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.beans.factory.ObjectProvider;

class PptImageServiceTest {
    @Test
    void persistsImageUrlOnceAndSkipsItOnResume() throws Exception {
        var model = mock(ImageModel.class);
        @SuppressWarnings("unchecked") ObjectProvider<ImageModel> models = mock(ObjectProvider.class);
        var minio = mock(MinioClient.class);
        var properties = mock(MinioProperties.class);
        when(properties.bucket()).thenReturn("sprout-test");
        when(models.getIfAvailable()).thenReturn(model);
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        // A valid 1x1 PNG; image bytes are stored, not an expiring model URL.
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL/nwAAAABJRU5ErkJggg==";
        when(model.call(any(ImagePrompt.class))).thenReturn(new ImageResponse(
                List.of(new ImageGeneration(new Image(null, png)))));
        var schema = (ObjectNode) new ObjectMapper().readTree("""
                {"slides":[{"data":{"封面插画":{"type":"image","content":"绿色能源插画","url":""}}}]}
                """);
        var service = new PptImageService(models, rewritingModel(), minio, properties, "https://slides.example.com");
        AtomicInteger checkpoints = new AtomicInteger();
        String conversationId = "a".repeat(32);
        var progress = new ArrayList<String>();
        service.fillMissingImages(schema, conversationId, updated -> {
            checkpoints.incrementAndGet();
            assertTrue(updated.path("slides").get(0).path("data").path("封面插画")
                    .path("url").asText().startsWith("https://slides.example.com/api/ppt/images/"));
        }, progress::add);
        service.fillMissingImages(schema, conversationId, updated -> checkpoints.incrementAndGet());
        assertEquals(1, checkpoints.get());
        verify(model, times(1)).call(any(ImagePrompt.class));
        verify(minio, times(1)).putObject(any(PutObjectArgs.class));
        assertEquals(List.of("第 1/1 张配图：正在优化配图描述（第 1/3 次）",
                "第 1/1 张配图：正在调用文生图模型（第 1/3 次）",
                "第 1/1 张配图：图片已生成，正在上传到 MinIO",
                "第 1/1 张配图：已保存"), progress);
    }
    @Test
    void contentInspectionRejectionPreservesCheckpointAndExplainsNextAction() throws Exception {
        var model = mock(ImageModel.class);
        @SuppressWarnings("unchecked") ObjectProvider<ImageModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        when(model.call(any(ImagePrompt.class))).thenThrow(new IllegalStateException(
                "HTTP 400 - {\"error\":{\"code\":\"DataInspectionFailed\",\"message\":\"Green net check rejected image (output)\"}}"));
        var schema = (ObjectNode) new ObjectMapper().readTree("""
                {"slides":[{"data":{"old":{"type":"image","content":"已有配图","url":"https://example.com/ready.png"},
                "new":{"type":"image","content":"待生成配图","url":""}}}]}
                """);
        var service = new PptImageService(models, rewritingModel(), mock(MinioClient.class),
                mock(MinioProperties.class), "https://slides.example.com");
        var error = assertThrows(IllegalStateException.class,
                () -> service.fillMissingImages(schema, "a".repeat(32)));
        assertTrue(error.getMessage().contains("安全审核"));
        assertTrue(error.getMessage().contains("连续 3 次"));
        assertEquals("https://example.com/ready.png",
                schema.path("slides").get(0).path("data").path("old").path("url").asText());
        assertTrue(schema.path("slides").get(0).path("data").path("new").path("url").asText().isEmpty());
        verify(model, times(3)).call(any(ImagePrompt.class));
    }

    @Test
    void inspectionRejectionRewritesAgainAndSucceedsOnSecondAttempt() throws Exception {
        var model = mock(ImageModel.class);
        @SuppressWarnings("unchecked") ObjectProvider<ImageModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        var chat = mock(ChatModel.class);
        var first = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        var second = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(first.getResult().getOutput().getText()).thenReturn("第一版画面");
        when(second.getResult().getOutput().getText()).thenReturn("不同的安全抽象画面");
        when(chat.call(any(Prompt.class))).thenReturn(first, second);
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScL/nwAAAABJRU5ErkJggg==";
        when(model.call(any(ImagePrompt.class))).thenThrow(new IllegalStateException("DataInspectionFailed"))
                .thenReturn(new ImageResponse(List.of(new ImageGeneration(new Image(null, png)))));
        var minio = mock(MinioClient.class);
        var properties = mock(MinioProperties.class);
        when(properties.bucket()).thenReturn("sprout-test");
        when(minio.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        var schema = (ObjectNode) new ObjectMapper().readTree("""
                {"slides":[{"data":{"image":{"type":"image","content":"一张插画","url":""}}}]}
                """);
        var progress = new ArrayList<String>();
        new PptImageService(models, chat, minio, properties, "https://slides.example.com")
                .fillMissingImages(schema, "a".repeat(32), ignored -> {}, progress::add);
        var prompts = ArgumentCaptor.forClass(ImagePrompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertEquals(List.of("第一版画面", "不同的安全抽象画面"),
                prompts.getAllValues().stream().map(p -> p.getInstructions().get(0).getText()).toList());
        var rewrites = ArgumentCaptor.forClass(Prompt.class);
        verify(chat, times(2)).call(rewrites.capture());
        assertTrue(rewrites.getAllValues().get(1).toString().contains("第一版画面"));
        assertTrue(progress.stream().anyMatch(p -> p.contains("未通过审核")));
        assertTrue(schema.path("slides").get(0).path("data").path("image").path("url")
                .asText().startsWith("https://slides.example.com/api/ppt/images/"));
        verify(minio, times(1)).putObject(any(PutObjectArgs.class));
    }

    @Test
    void rewrittenPromptIsSentToImageModelInsteadOfOriginal() throws Exception {
        var model = mock(ImageModel.class);
        @SuppressWarnings("unchecked") ObjectProvider<ImageModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        when(model.call(any(ImagePrompt.class))).thenThrow(new IllegalStateException("stop after observing prompt"));
        var chat = rewritingModel();
        var schema = (ObjectNode) new ObjectMapper().readTree("""
                {"slides":[{"data":{"image":{"type":"image","content":"原始配图需求","url":""}}}]}
                """);
        var service = new PptImageService(models, chat, mock(MinioClient.class),
                mock(MinioProperties.class), "https://slides.example.com");
        assertThrows(IllegalStateException.class, () -> service.fillMissingImages(schema, "a".repeat(32)));
        var imagePrompt = ArgumentCaptor.forClass(ImagePrompt.class);
        verify(model).call(imagePrompt.capture());
        assertEquals("适合公开展示的绿色植物插画", imagePrompt.getValue().getInstructions().get(0).getText());
        var rewritePrompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(rewritePrompt.capture());
        assertTrue(rewritePrompt.getValue().toString().contains("原始配图需求"));
    }

    @Test
    void rewriteFailureDoesNotSendUnreviewedPromptToImageModel() throws Exception {
        var model = mock(ImageModel.class);
        @SuppressWarnings("unchecked") ObjectProvider<ImageModel> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        var chat = mock(ChatModel.class);
        when(chat.call(any(Prompt.class))).thenThrow(new IllegalStateException("chat unavailable"));
        var schema = (ObjectNode) new ObjectMapper().readTree("""
                {"slides":[{"data":{"image":{"type":"image","content":"原始配图需求","url":""}}}]}
                """);
        var service = new PptImageService(models, chat, mock(MinioClient.class),
                mock(MinioProperties.class), "https://slides.example.com");
        var error = assertThrows(IllegalStateException.class,
                () -> service.fillMissingImages(schema, "a".repeat(32)));
        assertTrue(error.getMessage().contains("配图描述优化失败"));
        verify(model, org.mockito.Mockito.never()).call(any(ImagePrompt.class));
        assertTrue(schema.path("slides").get(0).path("data").path("image").path("url").asText().isEmpty());
    }

    private static ChatModel rewritingModel() {
        var chat = mock(ChatModel.class);
        var response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn("适合公开展示的绿色植物插画");
        when(chat.call(any(Prompt.class))).thenReturn(response);
        return chat;
    }
}
