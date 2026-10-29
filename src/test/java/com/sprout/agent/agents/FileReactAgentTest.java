package com.sprout.agent.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import com.sprout.agent.service.FileVectorService;
import com.sprout.agent.service.UploadedFileService;
import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.tool.FileContentTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
class FileReactAgentTest {

    @Test
    void loadsSmallFileOnlyThroughLocalTool() {
        FileVectorService vectors = mock(FileVectorService.class);
        FileReactAgent agent = fileAgent(mock(UploadedFileService.class), vectors);
        UploadedFileEntity document = document("note.txt", "text/plain", "附件原文", false);

        String message = agent.prepareUserMessage("请概括", document).getText();
        assertTrue(message.contains("fileId: file-1"));
        assertFalse(message.contains("附件原文"));
        ToolCallback[] callbacks = agent.toolCallbacks(document);
        assertEquals(1, callbacks.length);
        assertEquals("loadContent", callbacks[0].getToolDefinition().name());
        assertEquals("\"附件原文\"", callbacks[0].call("{\"fileId\":\"file-1\",\"question\":\"概括\"}"));
        verifyNoInteractions(vectors);
    }

    @Test
    void retrievesIndexedFileForEachDifferentQuestionAndRejectsAnotherFileId() {
        FileVectorService vectors = mock(FileVectorService.class);
        FileReactAgent agent = fileAgent(mock(UploadedFileService.class), vectors);
        UploadedFileEntity indexed = document("large.pdf", "application/pdf", null, true);
        when(vectors.retrieve("file-1", "第一章")).thenReturn("第一章内容");
        when(vectors.retrieve("file-1", "结论")).thenReturn("结论内容");
        ToolCallback callback = agent.toolCallbacks(indexed)[0];

        assertEquals("\"第一章内容\"", callback.call("{\"fileId\":\"file-1\",\"question\":\"第一章\"}"));
        assertEquals("\"结论内容\"", callback.call("{\"fileId\":\"file-1\",\"question\":\"结论\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> new FileContentTool(indexed, vectors).loadContent("another-file", "结论"));
        verify(vectors).retrieve("file-1", "第一章");
        verify(vectors).retrieve("file-1", "结论");
    }

    @Test
    void servesCachedImageDescriptionThroughToolWithoutExposingItInPrompt() {
        UploadedFileService files = mock(UploadedFileService.class);
        FileVectorService vectors = mock(FileVectorService.class);
        FileReactAgent agent = fileAgent(files, vectors);
        UploadedFileEntity image = document("photo.png", "image/png", null, false);
        when(files.imageDescription(org.mockito.ArgumentMatchers.eq(image),
                org.mockito.ArgumentMatchers.any())).thenReturn("一株幼苗");

        assertFalse(agent.prepareUserMessage("图片是什么", image).getText().contains("一株幼苗"));
        assertEquals("\"一株幼苗\"", agent.toolCallbacks(image)[0]
                .call("{\"fileId\":\"file-1\",\"question\":\"图片是什么\"}"));
        verify(files).imageDescription(org.mockito.ArgumentMatchers.eq(image),
                org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(vectors);
    }

    private UploadedFileEntity document(String name, String type, String text, boolean indexed) {
        UploadedFileEntity file = new UploadedFileEntity();
        file.setId("file-1");
        file.setName(name);
        file.setContentType(type);
        file.setExtractedText(text);
        file.setIndexed(indexed);
        return file;
    }

    private FileReactAgent fileAgent(UploadedFileService files, FileVectorService vectors) {
        return new FileReactAgent(mock(ObjectProvider.class),
                mock(ConversationService.class), mock(AgentTaskManager.class),
                files, vectors, new ObjectMapper(),
                "api-key", "model", 5, 20, true, "", "", "local-placeholder");
    }
}
