package com.sprout.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sprout.agent.entity.ChatMessageEntity;
import com.sprout.agent.entity.ConversationEntity;
import com.sprout.agent.mapper.ChatMessageMapper;
import com.sprout.agent.mapper.ChatTurnDetailMapper;
import com.sprout.agent.mapper.ConversationMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationService {

    private static final int HISTORY_LIMIT = 50;
    private static final int TITLE_LENGTH = 50;

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatTurnDetailMapper detailMapper;

    public ConversationService(ConversationMapper conversationMapper, ChatMessageMapper messageMapper,
            ChatTurnDetailMapper detailMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.detailMapper = detailMapper;
    }

    @Transactional
    public String createConversation(String conversationId, String question) {
        LocalDateTime now = LocalDateTime.now();
        ConversationEntity conversation;
        if (conversationId == null) {
            conversation = new ConversationEntity();
            conversation.setId(UUID.randomUUID().toString().replace("-", ""));
            conversation.setTitle(question.substring(0, Math.min(question.length(), TITLE_LENGTH)));
            conversation.setCreatedAt(now);
            conversation.setUpdatedAt(now);
            conversationMapper.insert(conversation);
        } else {
            conversation = requireConversation(conversationId);
        }
        return conversation.getId();
    }

    @Transactional
    public void saveExchange(String conversationId, String question, TurnResult result) {
        ConversationEntity conversation = requireConversation(conversationId);
        LocalDateTime now = LocalDateTime.now();
        insertMessage(conversationId, "user", question, now);
        ChatMessageEntity answer = insertMessage(conversationId, "assistant", result.answer(), now);
        detailMapper.insert(answer.getId(), result.thinking(), result.toolsJson(), result.referencesJson(),
                result.firstResponseMs(), result.totalResponseMs());
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
    }

    public List<ConversationSummary> listConversations() {
        return conversationMapper.selectList(new LambdaQueryWrapper<ConversationEntity>()
                        .orderByDesc(ConversationEntity::getUpdatedAt)
                        .last("LIMIT " + HISTORY_LIMIT))
                .stream()
                .map(conversation -> new ConversationSummary(
                        conversation.getId(), conversation.getTitle(), conversation.getUpdatedAt()))
                .toList();
    }

    public ChatMemory createPersistentChatMemory(String conversationId, int maxMessages) {
        if (maxMessages < 2 || maxMessages % 2 != 0) {
            throw new IllegalArgumentException("maxMessages must be a positive even number");
        }
        requireConversation(conversationId);
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        List<ChatMessageEntity> recent = messageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .orderByDesc(ChatMessageEntity::getId)
                .last("LIMIT " + maxMessages));
        UserMessage question = null;
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessageEntity message = recent.get(i);
            if ("user".equals(message.getRole())) {
                question = new UserMessage(message.getContent());
            } else if ("assistant".equals(message.getRole()) && question != null) {
                memory.add(conversationId, List.of(question, new AssistantMessage(message.getContent())));
                question = null;
            }
        }
        return memory;
    }

    public ConversationDetail getConversation(String id) {
        ConversationEntity conversation = requireConversation(id);
        List<MessageView> messages = messageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, id)
                        .orderByAsc(ChatMessageEntity::getId))
                .stream()
                .map(message -> new MessageView(message.getRole(), message.getContent()))
                .toList();
        return new ConversationDetail(conversation.getId(), conversation.getTitle(), messages);
    }

    @Transactional
    public void deleteConversation(String id) {
        requireConversation(id);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, id));
        conversationMapper.deleteById(id);
    }

    private ConversationEntity requireConversation(String id) {
        ConversationEntity conversation = conversationMapper.selectById(id);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在");
        }
        return conversation;
    }

    private ChatMessageEntity insertMessage(String conversationId, String role, String content, LocalDateTime createdAt) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        messageMapper.insert(message);
        return message;
    }

    public record TurnResult(String answer, String thinking, String toolsJson, String referencesJson,
            Long firstResponseMs, long totalResponseMs) {
    }

    public record ConversationSummary(String id, String title, LocalDateTime updatedAt) {
    }

    public record MessageView(String role, String text) {
    }

    public record ConversationDetail(String id, String title, List<MessageView> messages) {
    }
}
