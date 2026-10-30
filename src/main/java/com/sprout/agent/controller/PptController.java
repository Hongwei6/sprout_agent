package com.sprout.agent.controller;

import com.sprout.agent.agents.ppt.AiPptInst;
import com.sprout.agent.agents.ppt.PptBuilderAgent;
import com.sprout.agent.agents.ppt.PptInstService;
import com.sprout.agent.agents.ppt.PptInstStatus;
import com.sprout.agent.agents.ppt.PptRenderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ppt")
public class PptController {

    private final PptBuilderAgent agent;
    private final PptInstService pptInstService;
    private final PptRenderService renderer;

    public PptController(PptBuilderAgent agent, PptInstService pptInstService, PptRenderService renderer) {
        this.agent = agent;
        this.pptInstService = pptInstService;
        this.renderer = renderer;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> create(@Valid @RequestBody PptRequest request) {
        if (!agent.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(agent.stream(request.conversationId(), request.message())
                .map(event -> ServerSentEvent.<String>builder().event(event.name()).data(event.data()).build()));
    }

    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<PptInstanceView> getLatest(
            @PathVariable @Pattern(regexp = "[0-9a-f]{32}") String conversationId) {
        AiPptInst inst = pptInstService.getLatestInst(conversationId);
        if (inst == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(new PptInstanceView(inst.getStatus().name(), inst.getRequirement(),
                inst.getSearchInfo(), inst.getTemplateCode(), inst.getOutline(), inst.getPptSchema(),
                inst.getError()));
    }
    @GetMapping("/conversations/{conversationId}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable @Pattern(regexp = "[0-9a-f]{32}") String conversationId) {
        AiPptInst inst = pptInstService.getLatestInst(conversationId);
        if (inst == null || inst.getStatus() != PptInstStatus.SUCCESS) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sprout-" + conversationId + ".pptx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                .body(output -> {
                    try (var input = renderer.download(inst.getId())) {
                        input.transferTo(output);
                    }
                });
    }

    public record PptRequest(
            @Pattern(regexp = "[0-9a-f]{32}") String conversationId,
            @NotBlank @Size(max = 4000) String message) {
    }

    public record PptInstanceView(String status, String requirement, String searchInfo,
                                  String templateCode, String outline, String pptSchema, String error) {
    }
}
