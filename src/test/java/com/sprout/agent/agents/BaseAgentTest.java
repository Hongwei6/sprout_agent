package com.sprout.agent.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.dto.SearchResult;
import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import com.sprout.agent.service.UploadedFileService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import com.sprout.agent.service.FileVectorService;

class BaseAgentTest {

    @Test
    void mergesInterleavedToolArgumentFragmentsByCallId() {
        BaseAgent.RoundState state = new BaseAgent.RoundState();
        BaseAgent.mergeToolCall(state,
                new AssistantMessage.ToolCall("first", "function", "tavily_search", "{\"query\":\"Tav"));
        BaseAgent.mergeToolCall(state,
                new AssistantMessage.ToolCall("second", "function", "tavily_search", "{\"query\":\"Spring"));
        BaseAgent.mergeToolCall(state,
                new AssistantMessage.ToolCall("first", "function", "tavily_search", "ily\"}"));
        BaseAgent.mergeToolCall(state,
                new AssistantMessage.ToolCall("second", "function", "tavily_search", " AI\"}"));

        assertEquals(2, state.toolCalls.size());
        assertEquals("{\"query\":\"Tavily\"}", state.toolCalls.get(0).arguments());
        assertEquals("{\"query\":\"Spring AI\"}", state.toolCalls.get(1).arguments());
    }

    @Test
    void extractsMcpSearchReferencesFromNestedJsonContent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String result = mapper.writeValueAsString(List.of(Map.of("text", mapper.writeValueAsString(
                Map.of("results", List.of(
                        Map.of("url", "https://docs.tavily.com/agents", "title", "Agents", "content", "Agent guide"),
                        Map.of("url", "javascript:alert(1)", "title", "Bad", "content", "Not a source"),
                        Map.of("url", "https://docs.tavily.com/api", "title", "API", "content", "API guide")))))));

        assertEquals(List.of(new SearchResult("https://docs.tavily.com/agents", "Agents", "Agent guide"),
                        new SearchResult("https://docs.tavily.com/api", "API", "API guide")),
                BaseAgent.parseSearchResults(result, mapper));
    }

    @Test
    void agentsUseDistinctFileAndWebContexts() {
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        WebSearchReactAgent web = new WebSearchReactAgent(mock(ObjectProvider.class),
                mock(ConversationService.class), mock(AgentTaskManager.class),
                mock(UploadedFileService.class), new ObjectMapper(), provider,
                "api-key", "model", 5, 20, true);
        FileReactAgent files = new FileReactAgent(mock(ObjectProvider.class),
                mock(ConversationService.class), mock(AgentTaskManager.class),
                mock(UploadedFileService.class), mock(FileVectorService.class), new ObjectMapper(),
                "api-key", "model", 5, 20, true, "", "", "local-placeholder");
        UploadedFileEntity document = new UploadedFileEntity();
        document.setName("note.txt");
        document.setContentType("text/plain");
        document.setExtractedText("附件内容");

        assertTrue(web.systemPrompt().contains("联网搜索"));
        assertTrue(files.systemPrompt().contains("文件处理规则"));
        assertEquals("问题", web.prepareUserMessage("问题", null).getText());
        assertTrue(files.prepareUserMessage("问题", document).getText().contains("fileId"));
        assertFalse(files.prepareUserMessage("问题", document).getText().contains("附件内容"));
        assertFalse(web.systemPrompt().contains("source-check"));
        assertTrue(java.util.Arrays.stream(web.toolCallbacks(null))
                .anyMatch(tool -> tool.getToolDefinition().name().equals("bash")));
        assertTrue(java.util.Arrays.stream(web.toolCallbacks(null))
                .anyMatch(tool -> tool.getToolDefinition().name().equals("write_file")));
        assertTrue(web.systemPrompt().contains("宿主机"));
        assertEquals(1, files.toolCallbacks(document).length);
        assertFalse(web.systemPrompt().contains("文件处理规则"));
    }
    @Test
    void ordinaryChatCanRunHostScriptAndWriteRequestedFile(@TempDir Path directory) throws Exception {
        SyncMcpToolCallbackProvider provider = mock(SyncMcpToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(new org.springframework.ai.tool.ToolCallback[0]);
        WebSearchReactAgent web = new WebSearchReactAgent(mock(ObjectProvider.class),
                mock(ConversationService.class), mock(AgentTaskManager.class),
                mock(UploadedFileService.class), new ObjectMapper(), provider,
                "api-key", "model", 5, 20, true);
        Path script = directory.resolve("generate.sh");
        Files.writeString(script, "printf 'generated on host' > result.txt\n");
        org.springframework.ai.tool.ToolCallback bash = java.util.Arrays.stream(web.toolCallbacks(null))
                .filter(tool -> tool.getToolDefinition().name().equals("bash")).findFirst().orElseThrow();
        String input = new ObjectMapper().writeValueAsString(Map.of(
                "command", "bash generate.sh", "workingDirectory", directory.toString()));
        assertTrue(bash.call(input).contains("Exit code 0"));
        assertEquals("generated on host", Files.readString(directory.resolve("result.txt")));
    }

}
