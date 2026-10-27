package com.sprout.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sprout.agent.entity.ChatMessageEntity;
import com.sprout.agent.entity.ConversationEntity;
import com.sprout.agent.mapper.ChatMessageMapper;
import com.sprout.agent.mapper.ChatTurnDetailMapper;
import com.sprout.agent.mapper.ConversationMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

class ConversationServiceTest {

    @Test
    void completedExchangesReloadInOrderAndWindowExcludesOlderTurns() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        ChatTurnDetailMapper details = mock(ChatTurnDetailMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("session");
        when(conversations.selectById("session")).thenReturn(conversation);
        List<ChatMessageEntity> rows = new ArrayList<>();
        when(messages.insert(any(ChatMessageEntity.class))).thenAnswer(invocation -> {
            ChatMessageEntity inserted = invocation.getArgument(0);
            inserted.setId((long) rows.size() + 1);
            rows.add(inserted);
            return 1;
        });
        when(messages.selectList(any())).thenAnswer(invocation -> {
            List<ChatMessageEntity> recent = new ArrayList<>(rows);
            Collections.reverse(recent);
            return recent;
        });

        ConversationService service = new ConversationService(conversations, messages, details);
        service.saveExchange("session", "第一问", result("第一答"));
        service.saveExchange("session", "第二问", result("第二答"));
        service.saveExchange("session", "第三问", result("第三答"));

        ChatMemory memory = service.createPersistentChatMemory("session", 4);
        List<Message> restored = memory.get("session");
        assertEquals(4, restored.size());
        assertEquals("第二问", assertInstanceOf(UserMessage.class, restored.get(0)).getText());
        assertEquals("第二答", assertInstanceOf(AssistantMessage.class, restored.get(1)).getText());
        assertEquals("第三问", assertInstanceOf(UserMessage.class, restored.get(2)).getText());
        assertEquals("第三答", assertInstanceOf(AssistantMessage.class, restored.get(3)).getText());
        assertEquals(6, rows.size());

        ChatMemory afterRestart = service.createPersistentChatMemory("session", 2);
        assertEquals("第三问", afterRestart.get("session").get(0).getText());
        assertEquals("第三答", afterRestart.get("session").get(1).getText());
    }

    @Test
    void ignoresLegacyUnansweredQuestionWhileRebuildingMemory() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        ChatTurnDetailMapper details = mock(ChatTurnDetailMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("session");
        when(conversations.selectById("session")).thenReturn(conversation);
        ChatMessageEntity completedAnswer = row("assistant", "已完成答案");
        ChatMessageEntity completedQuestion = row("user", "已完成问题");
        when(messages.selectList(any())).thenReturn(List.of(
                row("user", "旧的失败请求"), completedAnswer, completedQuestion));

        List<Message> restored = new ConversationService(conversations, messages, details)
                .createPersistentChatMemory("session", 4).get("session");
        assertEquals(2, restored.size());
        assertEquals("已完成问题", restored.get(0).getText());
        assertEquals("已完成答案", restored.get(1).getText());
    }

    private static ConversationService.TurnResult result(String answer) {
        return new ConversationService.TurnResult(answer, null, "[]", "[]", 10L, 20);
    }

    private static ChatMessageEntity row(String role, String text) {
        ChatMessageEntity row = new ChatMessageEntity();
        row.setRole(role);
        row.setContent(text);
        return row;
    }
}
