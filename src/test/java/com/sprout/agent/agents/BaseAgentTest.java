package com.sprout.agent.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.dto.SearchResult;
import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import com.sprout.agent.service.UploadedFileService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

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
        WebSearchReactAgent web = new WebSearchReactAgent(mock(ObjectProvider.class),
                mock(ConversationService.class), mock(AgentTaskManager.class),
                mock(UploadedFileService.class), new ObjectMapper(), mock(SyncMcpToolCallbackProvider.class),
                "api-key", "model", 5, 20, true);
        FileReactAgent files = new FileReactAgent(mock(ObjectProvider.class),
                mock(ConversationService.class), mock(AgentTaskManager.class),
                mock(UploadedFileService.class), new ObjectMapper(), mock(SyncMcpToolCallbackProvider.class),
                "api-key", "model", 5, 20, true, "", "", "local-placeholder");
        UploadedFileEntity document = new UploadedFileEntity();
        document.setName("note.txt");
        document.setContentType("text/plain");
        document.setExtractedText("附件内容");

        assertTrue(web.systemPrompt().contains("联网搜索"));
        assertTrue(files.systemPrompt().contains("文件处理规则"));
        assertEquals("问题", web.prepareUserMessage("问题", null).getText());
        assertTrue(files.prepareUserMessage("问题", document).getText().contains("附件内容"));
        assertFalse(web.systemPrompt().contains("文件处理规则"));
    }
}
