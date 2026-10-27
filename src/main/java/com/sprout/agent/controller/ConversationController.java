package com.sprout.agent.controller;

import com.sprout.agent.service.ConversationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
    public List<ConversationService.ConversationSummary> list(
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "") String query) {
        return conversationService.listConversations(archived, query);
    }

    @PatchMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable @Pattern(regexp = "[0-9a-f]{32}") String id) {
        conversationService.setArchived(id, true);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable @Pattern(regexp = "[0-9a-f]{32}") String id) {
        conversationService.setArchived(id, false);
        return ResponseEntity.noContent().build();
    }
    @PatchMapping("/{id}/title")
    public ResponseEntity<Void> rename(@PathVariable @Pattern(regexp = "[0-9a-f]{32}") String id,
            @Valid @RequestBody RenameRequest request) {
        conversationService.renameConversation(id, request.title());
        return ResponseEntity.noContent().build();
    }

    public record RenameRequest(@NotBlank @Size(max = 50) String title) {
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
