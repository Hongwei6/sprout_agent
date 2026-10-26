package com.sprout.agent.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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

    public ConversationService(ConversationMapper conversationMapper, ChatMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public String saveUserMessage(String conversationId, String content) {
        LocalDateTime now = LocalDateTime.now();
        ConversationEntity conversation;
        if (conversationId == null) {
            conversation = new ConversationEntity();
            conversation.setId(UUID.randomUUID().toString().replace("-", ""));
            conversation.setTitle(content.substring(0, Math.min(content.length(), TITLE_LENGTH)));
            conversation.setCreatedAt(now);
            conversation.setUpdatedAt(now);
            conversationMapper.insert(conversation);
        } else {
            conversation = requireConversation(conversationId);
            conversation.setUpdatedAt(now);
            conversationMapper.updateById(conversation);
        }
        insertMessage(conversation.getId(), "user", content, now);
        return conversation.getId();
    }

    @Transactional
    public void saveAssistantMessage(String conversationId, String content) {
        ConversationEntity conversation = requireConversation(conversationId);
        LocalDateTime now = LocalDateTime.now();
        insertMessage(conversationId, "assistant", content, now);
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

    public List<MessageView> recentPromptMessages(String conversationId) {
        requireConversation(conversationId);
        List<MessageView> recent = new ArrayList<>(messageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .orderByDesc(ChatMessageEntity::getId)
                        .last("LIMIT 20"))
                .stream()
                .map(message -> new MessageView(message.getRole(), message.getContent()))
                .toList());
        Collections.reverse(recent);
        return recent;
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

    private void insertMessage(String conversationId, String role, String content, LocalDateTime createdAt) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        messageMapper.insert(message);
    }

    public record ConversationSummary(String id, String title, LocalDateTime updatedAt) {
    }

    public record MessageView(String role, String text) {
    }

    public record ConversationDetail(String id, String title, List<MessageView> messages) {
    }
}
