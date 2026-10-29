package com.sprout.agent.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.prompts.ReactAgentPrompts;
import com.sprout.agent.service.AgentTaskManager;
import com.sprout.agent.service.ConversationService;
import com.sprout.agent.service.UploadedFileService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class WebSearchReactAgent extends BaseAgent {

    public WebSearchReactAgent(ObjectProvider<ChatClient.Builder> chatClientBuilder,
            ConversationService conversationService, AgentTaskManager taskManager,
            UploadedFileService fileService,
            ObjectMapper objectMapper, SyncMcpToolCallbackProvider toolProvider,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:}") String model,
            @Value("${sprout.agent.max-rounds:5}") int maxRounds,
            @Value("${sprout.agent.max-messages:20}") int maxMessages,
            @Value("${sprout.agent.enable-recommendations:true}") boolean enableRecommendations) {
        super(chatClientBuilder, conversationService, taskManager, fileService,
                objectMapper, toolProvider, apiKey, model, maxRounds, maxMessages, enableRecommendations);
    }

    public Flux<ChatEvent> stream(String conversationId, String question) {
        return streamAgent(conversationId, question, null);
    }

    @Override
    protected String systemPrompt() {
        return ReactAgentPrompts.getWebSearchPrompt();
    }

    @Override
    protected UserMessage prepareUserMessage(String question, UploadedFileEntity file) {
        return new UserMessage(question);
    }
}
