package com.sprout.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
