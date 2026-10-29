package com.sprout.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import com.sprout.agent.agents.FileReactAgent;
import com.sprout.agent.agents.WebSearchReactAgent;
import com.sprout.agent.service.AgentTaskManager;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import reactor.core.publisher.Flux;

class ChatControllersTest {

    @Test
    void webChatUsesOnlyWebAgent() {
        WebSearchReactAgent web = mock(WebSearchReactAgent.class);
        when(web.isAvailable()).thenReturn(true);
        when(web.stream("conversation", "question")).thenReturn(Flux.just(new ChatEvent("delta", "answer")));
        ChatController controller = new ChatController(web, mock(AgentTaskManager.class));

        assertEquals("/api/chat", ChatController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertEquals("answer", controller.chat(new ChatController.ChatRequest("conversation", "question"))
                .getBody().blockFirst().data());
        verify(web).stream("conversation", "question");
    }

    @Test
    void fileChatRequiresFileAndUsesOnlyFileAgent() {
        FileReactAgent file = mock(FileReactAgent.class);
        when(file.isAvailable()).thenReturn(true);
        when(file.stream("conversation", "question", "file-id"))
                .thenReturn(Flux.just(new ChatEvent("delta", "document answer")));
        FileChatController controller = new FileChatController(file);

        assertEquals("/api/file-chat", FileChatController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertEquals("document answer", controller.chat(
                new FileChatController.FileChatRequest("conversation", "question", "file-id"))
                .getBody().blockFirst().data());
        verify(file).stream("conversation", "question", "file-id");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(
                    new FileChatController.FileChatRequest(null, "question", null)).isEmpty());
        }
    }

    @Test
    void sharedStopRouteStopsTasksFromEitherAgent() {
        AgentTaskManager tasks = mock(AgentTaskManager.class);
        when(tasks.stopTask("conversation")).thenReturn(true);

        assertEquals(204, new ChatController(mock(WebSearchReactAgent.class), tasks)
                .stop("conversation").getStatusCode().value());
        verify(tasks).stopTask("conversation");
    }
}
