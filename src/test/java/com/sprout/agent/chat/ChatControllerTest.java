package com.sprout.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class ChatControllerTest {

    @Test
    void mergesInterleavedToolArgumentFragmentsByCallId() {
        ChatController.RoundState state = new ChatController.RoundState();
        ChatController.mergeToolCall(state,
                new AssistantMessage.ToolCall("first", "function", "searchWeb", "{\"query\":\"Tav"));
        ChatController.mergeToolCall(state,
                new AssistantMessage.ToolCall("second", "function", "searchWeb", "{\"query\":\"Spring"));
        ChatController.mergeToolCall(state,
                new AssistantMessage.ToolCall("first", "function", "searchWeb", "ily\"}"));
        ChatController.mergeToolCall(state,
                new AssistantMessage.ToolCall("second", "function", "searchWeb", " AI\"}"));

        assertEquals(2, state.toolCalls.size());
        assertEquals("{\"query\":\"Tavily\"}", state.toolCalls.get(0).arguments());
        assertEquals("{\"query\":\"Spring AI\"}", state.toolCalls.get(1).arguments());
    }

    @Test
    void extractsSearchReferencesFromJsonEncodedToolResult() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String result = mapper.writeValueAsString("Result one\nhttps://docs.tavily.com/agents\nSummary\n\n"
                + "Result two\nhttps://docs.tavily.com/documentation/api-reference\nSummary");

        assertEquals(List.of("https://docs.tavily.com/agents",
                        "https://docs.tavily.com/documentation/api-reference"),
                ChatController.extractSourceLinks(result, mapper));
    }
}
