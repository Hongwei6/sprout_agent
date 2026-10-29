package com.sprout.agent.controller;

import com.sprout.agent.agents.FileReactAgent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/file-chat")
public class FileChatController {

    private final FileReactAgent agent;

    public FileChatController(FileReactAgent agent) {
        this.agent = agent;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> chat(@Valid @RequestBody FileChatRequest request) {
        if (!agent.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(agent.stream(request.conversationId(), request.message(), request.fileId())
                .map(event -> ServerSentEvent.<String>builder().event(event.name()).data(event.data()).build()));
    }

    public record FileChatRequest(
            @Pattern(regexp = "[0-9a-f]{32}") String conversationId,
            @NotBlank @Size(max = 4000) String message,
            @NotNull @Pattern(regexp = "[0-9a-f]{32}") String fileId) {
    }
}
