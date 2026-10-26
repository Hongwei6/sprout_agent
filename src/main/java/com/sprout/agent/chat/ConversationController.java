package com.sprout.agent.chat;

import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public List<ConversationService.ConversationSummary> list() {
        return conversationService.listConversations();
    }

    @GetMapping("/{id}")
    public ConversationService.ConversationDetail get(
            @PathVariable @Pattern(regexp = "[0-9a-f]{32}") String id) {
        return conversationService.getConversation(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = "[0-9a-f]{32}") String id) {
        conversationService.deleteConversation(id);
        return ResponseEntity.noContent().build();
    }
}
