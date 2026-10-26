package com.sprout.agent.chat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final String apiKey;

    public ChatController(
            ObjectProvider<ChatClient.Builder> chatClientBuilder,
            @Value("${DOUBAO_API_KEY:}") String apiKey) {
        this.chatClientBuilder = chatClientBuilder;
        this.apiKey = apiKey;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (apiKey.isBlank() || builder == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatResponse("AI 服务尚未配置，请设置 DOUBAO_API_KEY 和 DOUBAO_MODEL。"));
        }

        String answer = builder
                .build()
                .prompt()
                .user(request.message())
                .call()
                .content();
        return ResponseEntity.ok(new ChatResponse(answer));
    }

    public record ChatRequest(@NotBlank @Size(max = 4000) String message) {
    }

    public record ChatResponse(String answer) {
    }
}
