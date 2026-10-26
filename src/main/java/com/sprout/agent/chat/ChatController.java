package com.sprout.agent.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final String LOCAL_API_KEY_PLACEHOLDER = "local-placeholder";

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ConversationService conversationService;
    private final String apiKey;
    private final String model;

    public ChatController(
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            ConversationService conversationService,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:}") String model) {
        this.chatClientBuilder = chatClientBuilder;
        this.conversationService = conversationService;
        this.apiKey = apiKey;
        this.model = model;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        String conversationId = conversationService.saveUserMessage(request.conversationId(), request.message());
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (apiKey.isBlank() || LOCAL_API_KEY_PLACEHOLDER.equals(apiKey) || model.isBlank() || builder == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatResponse(conversationId, "DeepSeek 服务尚未配置，请检查模型凭证。"));
        }

        List<Message> promptMessages = conversationService.recentPromptMessages(conversationId).stream()
                .map(message -> (Message) ("user".equals(message.role())
                        ? new UserMessage(message.text())
                        : new AssistantMessage(message.text())))
                .toList();
        String answer = builder.build().prompt().messages(promptMessages).call().content();
        conversationService.saveAssistantMessage(conversationId, answer);
        return ResponseEntity.ok(new ChatResponse(conversationId, answer));
    }

    public record ChatRequest(
            @Pattern(regexp = "[0-9a-f]{32}") String conversationId,
            @NotBlank @Size(max = 4000) String message) {
    }

    public record ChatResponse(String conversationId, String answer) {
    }
}
