package com.sprout.agent.controller;

import com.sprout.agent.agents.skills.SkillRegistry;
import com.sprout.agent.agents.skills.SkillsReactAgent;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/skills")
public class SkillsController {
    private final SkillRegistry registry;
    private final SkillsReactAgent agent;

    public SkillsController(SkillRegistry registry, SkillsReactAgent agent) {
        this.registry = registry;
        this.agent = agent;
    }

    @GetMapping
    public List<SkillSummary> list() {
        return registry.list().stream().map(skill -> new SkillSummary(skill.name(), skill.description())).toList();
    }

    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> chat(@Valid @RequestBody ChatController.ChatRequest request) {
        if (!agent.isAvailable()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        return ResponseEntity.ok(agent.stream(request.conversationId(), request.message())
                .map(event -> ServerSentEvent.<String>builder().event(event.name()).data(event.data()).build()));
    }

    public record SkillSummary(String name, String description) {}
}
