package com.sprout.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorFilterExpressionConverter;

class FileVectorServiceTest {

    @Test
    void indexesCompleteTextAsOverlappingChunksTaggedWithItsFileId() {
        VectorStore store = mock(VectorStore.class);
        FileVectorService files = new FileVectorService(store);
        String id = "0123456789abcdef0123456789abcdef";
        String text = "a".repeat(2_000) + "b".repeat(2_000) + "last";
        files.index(id, text);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captured = ArgumentCaptor.forClass(List.class);
        verify(store).add(captured.capture());
        List<Document> chunks = captured.getValue();
        assertEquals(3, chunks.size());
        assertEquals(text.substring(0, 2_000), chunks.get(0).getText());
        assertEquals(text.substring(1_800, 3_800), chunks.get(1).getText());
        assertEquals(text.substring(3_600), chunks.get(2).getText());
        assertTrue(chunks.stream().allMatch(chunk -> id.equals(chunk.getMetadata().get("fileId"))));
    }

    @Test
    void retrievesTopRelevantChunksOnlyFromRequestedFile() {
        VectorStore store = mock(VectorStore.class);
        FileVectorService files = new FileVectorService(store);
        String id = "0123456789abcdef0123456789abcdef";
        when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("relevant one", Map.of("fileId", id)),
                new Document("relevant two", Map.of("fileId", id))));

        assertEquals("relevant one\n\nrelevant two", files.retrieve(id, "question"));
        ArgumentCaptor<SearchRequest> captured = ArgumentCaptor.forClass(SearchRequest.class);
        verify(store).similaritySearch(captured.capture());
        SearchRequest request = captured.getValue();
        assertEquals("question", request.getQuery());
        assertEquals(5, request.getTopK());
        assertEquals("$.fileId == \"" + id + "\"",
                new PgVectorFilterExpressionConverter().convertExpression(request.getFilterExpression()));
    }

    @Test
    void rejectsUnsafeFileIdBeforeBuildingPgVectorSqlFilter() {
        VectorStore store = mock(VectorStore.class);
        FileVectorService files = new FileVectorService(store);
        assertThrows(IllegalArgumentException.class, () -> files.retrieve("' OR true --", "question"));
        verify(store, never()).similaritySearch(any(SearchRequest.class));
    }
}
