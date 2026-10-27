package com.sprout.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        String result = mapper.writeValueAsString(
                Map.of("content", List.of(Map.of("type", "text", "text",
                        "{\"results\":[{\"url\":\"https://docs.tavily.com/agents\"},"
                                + "{\"url\":\"https://docs.tavily.com/documentation/api-reference\"}]}"))));

        assertEquals(List.of("https://docs.tavily.com/agents",
                        "https://docs.tavily.com/documentation/api-reference"),
                ReactAgentService.extractSourceLinks(result, mapper));
    }
}
