package com.sprout.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sprout.agent.agents.BaseAgent.ChatEvent;
import com.sprout.agent.agents.skills.SkillRegistry;
import com.sprout.agent.agents.skills.SkillsReactAgent;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

class SkillsControllerTest {
    @TempDir Path directory;

    @Test
    void chatIsOpenWithoutTokenAndStreamsAnswer() {
        SkillsReactAgent agent = mock(SkillsReactAgent.class);
        when(agent.isAvailable()).thenReturn(true);
        when(agent.stream("conversation", "question")).thenReturn(Flux.just(new ChatEvent("delta", "answer")));
        SkillsController controller = new SkillsController(new SkillRegistry(directory), agent);
        assertEquals("answer", controller.chat(new ChatController.ChatRequest("conversation", "question"))
                .getBody().blockFirst().data());
        verify(agent).stream("conversation", "question");
        assertEquals(0, controller.list().size());
    }

    @Test
    void chatReportsUnavailableWhenAgentIsNotConfigured() {
        SkillsReactAgent agent = mock(SkillsReactAgent.class);
        when(agent.isAvailable()).thenReturn(false);
        SkillsController controller = new SkillsController(new SkillRegistry(directory), agent);
        assertEquals(503, controller.chat(new ChatController.ChatRequest(null, "question"))
                .getStatusCode().value());
    }
}
