package com.sprout.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class FileVectorService {

    private static final int CHUNK_CHARS = 2_000;
    private static final int OVERLAP_CHARS = 200;
    private static final int TOP_K = 5;

    private final VectorStore vectorStore;

    public FileVectorService(@Qualifier("fileVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void index(String fileId, String text) {
        List<Document> chunks = new ArrayList<>();
        Map<String, Object> metadata = Map.of("fileId", fileId);
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + CHUNK_CHARS, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            chunks.add(new Document(text.substring(start, end), metadata));
            if (end == text.length()) break;
            start = end - OVERLAP_CHARS;
            if (Character.isLowSurrogate(text.charAt(start))) start--;
        }
        vectorStore.add(chunks);
    }

    public String retrieve(String fileId, String question) {
        validateFileId(fileId);
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(question)
                        .topK(TOP_K)
                        .filterExpression("fileId == '" + fileId + "'")
                        .build()).stream()
                .map(Document::getText)
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    public void delete(String fileId) {
        validateFileId(fileId);
        vectorStore.delete("fileId == '" + fileId + "'");
    }

    private static void validateFileId(String fileId) {
        if (fileId == null || fileId.length() != 32) throw new IllegalArgumentException("文件 ID 无效");
        for (int i = 0; i < fileId.length(); i++) {
            char c = fileId.charAt(i);
            if (c < '0' || c > '9') {
                if (c < 'a' || c > 'f') throw new IllegalArgumentException("文件 ID 无效");
            }
        }
    }
}
