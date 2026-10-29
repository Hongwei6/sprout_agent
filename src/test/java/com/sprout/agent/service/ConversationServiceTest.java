package com.sprout.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sprout.agent.dto.SearchResult;
import com.sprout.agent.entity.ChatMessageEntity;
import com.sprout.agent.entity.UploadedFileEntity;
import com.sprout.agent.entity.ConversationEntity;
import com.sprout.agent.mapper.ChatMessageMapper;
import com.sprout.agent.mapper.ChatTurnDetailMapper;
import com.sprout.agent.mapper.ConversationMapper;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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

        ConversationService service = new ConversationService(conversations, messages, details, new ObjectMapper());
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

        List<Message> restored = new ConversationService(conversations, messages, details, new ObjectMapper())
                .createPersistentChatMemory("session", 4).get("session");
        assertEquals(2, restored.size());
        assertEquals("已完成问题", restored.get(0).getText());
        assertEquals("已完成答案", restored.get(1).getText());
    }

    @Test
    void conversationHistoryRestoresStructuredAndLegacyReferencesWithTheirAnswers() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        ChatTurnDetailMapper details = mock(ChatTurnDetailMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("session");
        when(conversations.selectById("session")).thenReturn(conversation);
        ChatMessageEntity question = row("user", "问题");
        question.setId(1L);
        question.setFileId("12345678901234567890123456789012");
        question.setFileName("说明.txt");
        ChatMessageEntity answer = row("assistant", "答案");
        answer.setId(2L);
        ChatMessageEntity olderAnswer = row("assistant", "旧答案");
        olderAnswer.setId(3L);
        when(messages.selectList(any())).thenReturn(List.of(question, answer, olderAnswer));
        when(details.findReferences("session")).thenReturn(List.of(
                new ChatTurnDetailMapper.ReferenceRow(2L,
                        "[{\"url\":\"https://docs.tavily.com\",\"title\":\"Tavily 文档\",\"content\":\"摘要\"}]",
                        "[\"继续了解 Tavily？\",\"如何配置搜索？\",\"还有哪些案例？\"]"),
                new ChatTurnDetailMapper.ReferenceRow(3L, "[\"https://example.org/legacy\"]", null)));

        List<ConversationService.MessageView> history =
                new ConversationService(conversations, messages, details, new ObjectMapper())
                        .getConversation("session").messages();

        assertEquals("12345678901234567890123456789012", history.get(0).fileId());
        assertEquals("说明.txt", history.get(0).fileName());
        assertEquals(null, history.get(1).fileId());
        assertEquals(List.of(), history.get(0).references());
        assertEquals(List.of(new SearchResult("https://docs.tavily.com", "Tavily 文档", "摘要")),
                history.get(1).references());
        assertEquals(List.of("继续了解 Tavily？", "如何配置搜索？", "还有哪些案例？"),
                history.get(1).recommendations());
        assertEquals(List.of(new SearchResult("https://example.org/legacy", "https://example.org/legacy", "")),
                history.get(2).references());
        assertEquals(List.of(), history.get(2).recommendations());
    }

    @Test
    void archivedConversationCannotContinueAndRestoringKeepsLastMessageTime() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        ChatTurnDetailMapper details = mock(ChatTurnDetailMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("session");
        LocalDateTime lastMessage = LocalDateTime.of(2025, 1, 1, 12, 0);
        conversation.setUpdatedAt(lastMessage);
        when(conversations.selectById("session")).thenReturn(conversation);
        ConversationService service = new ConversationService(conversations, messages, details, new ObjectMapper());

        service.setArchived("session", true);
        assertEquals(true, conversation.getArchived());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createConversation("session", "继续"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        service.setArchived("session", false);
        assertEquals(false, conversation.getArchived());
        assertEquals(lastMessage, conversation.getUpdatedAt());
        verify(conversations, org.mockito.Mockito.times(2)).updateById(conversation);
    }

    @Test
    void renamingKeepsLastMessageTimeAndRejectsBlankTitles() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("session");
        conversation.setUpdatedAt(LocalDateTime.of(2025, 1, 1, 12, 0));
        when(conversations.selectById("session")).thenReturn(conversation);
        ConversationService service = new ConversationService(conversations, mock(ChatMessageMapper.class),
                mock(ChatTurnDetailMapper.class), new ObjectMapper());

        service.renameConversation("session", "  新名字  ");
        assertEquals("新名字", conversation.getTitle());
        assertEquals(LocalDateTime.of(2025, 1, 1, 12, 0), conversation.getUpdatedAt());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.renameConversation("session", "  ")).getStatusCode());
        assertEquals("新名字", conversation.getTitle());
    }

    @Test
    void completedAttachmentKeepsFileMetadataOnlyOnUserTurn() {
        ConversationMapper conversations = mock(ConversationMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        ChatTurnDetailMapper details = mock(ChatTurnDetailMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId("session");
        when(conversations.selectById("session")).thenReturn(conversation);
        List<ChatMessageEntity> inserted = new ArrayList<>();
        when(messages.insert(any(ChatMessageEntity.class))).thenAnswer(invocation -> {
            ChatMessageEntity message = invocation.getArgument(0);
            message.setId((long) inserted.size() + 1);
            inserted.add(message);
            return 1;
        });
        UploadedFileEntity file = new UploadedFileEntity();
        file.setId("12345678901234567890123456789012");
        file.setName("报告.pdf");

        new ConversationService(conversations, messages, details, new ObjectMapper())
                .saveExchange("session", "概括报告", result("摘要"), file);

        assertEquals(file.getId(), inserted.get(0).getFileId());
        assertEquals(file.getName(), inserted.get(0).getFileName());
        assertEquals(null, inserted.get(1).getFileId());
        assertEquals("摘要", inserted.get(1).getContent());
    }

    private static ConversationService.TurnResult result(String answer) {
        return new ConversationService.TurnResult(answer, null, "[]", "[]", null, 10L, 20);
    }

    private static ChatMessageEntity row(String role, String text) {
        ChatMessageEntity row = new ChatMessageEntity();
        row.setRole(role);
        row.setContent(text);
        return row;
    }
}
