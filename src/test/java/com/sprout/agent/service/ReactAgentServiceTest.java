package com.sprout.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.dto.SearchResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class ReactAgentServiceTest {

    @Test
    void mergesInterleavedToolArgumentFragmentsByCallId() {
        ReactAgentService.RoundState state = new ReactAgentService.RoundState();
        ReactAgentService.mergeToolCall(state,
                new AssistantMessage.ToolCall("first", "function", "tavily_search", "{\"query\":\"Tav"));
        ReactAgentService.mergeToolCall(state,
                new AssistantMessage.ToolCall("second", "function", "tavily_search", "{\"query\":\"Spring"));
        ReactAgentService.mergeToolCall(state,
                new AssistantMessage.ToolCall("first", "function", "tavily_search", "ily\"}"));
        ReactAgentService.mergeToolCall(state,
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
                ReactAgentService.parseSearchResults(result, mapper));
    }
}
