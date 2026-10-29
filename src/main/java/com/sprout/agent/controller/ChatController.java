package com.sprout.agent.controller;

import com.sprout.agent.agents.WebSearchReactAgent;
import com.sprout.agent.service.AgentTaskManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final WebSearchReactAgent agentService;
    private final AgentTaskManager taskManager;

    public ChatController(WebSearchReactAgent agentService, AgentTaskManager taskManager) {
        this.agentService = agentService;
        this.taskManager = taskManager;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> chat(@Valid @RequestBody ChatRequest request) {
        if (!agentService.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(agentService.stream(request.conversationId(), request.message())
                .map(event -> ServerSentEvent.<String>builder().event(event.name()).data(event.data()).build()));
    }

    @PatchMapping("/{conversationId}/stop")
    public ResponseEntity<Void> stop(@PathVariable @Pattern(regexp = "[0-9a-f]{32}") String conversationId) {
        return taskManager.stopTask(conversationId)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record ChatRequest(
            @Pattern(regexp = "[0-9a-f]{32}") String conversationId,
            @NotBlank @Size(max = 4000) String message) {
    }
}
