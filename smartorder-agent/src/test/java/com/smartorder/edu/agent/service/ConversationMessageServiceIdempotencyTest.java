package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.mapper.ConversationMapper;
import com.smartorder.edu.agent.mapper.ConversationMessageMapper;
import com.smartorder.edu.agent.model.entity.Conversation;
import com.smartorder.edu.agent.model.entity.ConversationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * conversation_id + message_uuid 幂等语义：
 * 同 UUID 同内容 -> 返回已有行不插入；同 UUID 不同内容 -> 409。
 */
class ConversationMessageServiceIdempotencyTest {

    private static final String CONVERSATION = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String MESSAGE = "aaaaaaaa-1111-4111-8111-111111111111";
    private static final Long USER_ID = 7L;

    private ConversationMapper conversationMapper;
    private ConversationMessageMapper messageMapper;
    private ConversationMessageService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(ConversationMessageMapper.class);
        service = new ConversationMessageService(
                conversationMapper,
                messageMapper,
                mock(ConversationService.class),
                mock(ConversationDraftService.class));

        Conversation conversation = new Conversation();
        conversation.setId(100L);
        conversation.setConversationId(CONVERSATION);
        conversation.setUserId(USER_ID);
        conversation.setStatus(Conversation.STATUS_ACTIVE);
        conversation.setTitle("测试会话");
        conversation.setTitleSource(Conversation.TITLE_SOURCE_AUTO);
        when(conversationMapper.selectByConversationId(CONVERSATION)).thenReturn(conversation);
        when(conversationMapper.selectByConversationIdForUpdate(CONVERSATION)).thenReturn(conversation);
    }

    @Test
    void shouldReturnExistingMessageWithoutInsertingAgain() {
        ConversationMessage existing = userMessage("你好", "task-1");
        ConversationMessage assistant = assistantMessage("task-1");
        when(messageMapper.selectByMessageUuid(CONVERSATION, MESSAGE)).thenReturn(existing);
        when(messageMapper.selectAssistantByTaskId("task-1")).thenReturn(assistant);

        ConversationMessageService.PendingReply reply = service.openReply(
                CONVERSATION, MESSAGE, null, USER_ID, "你好", null, "trace-1", "task-new");

        assertTrue(reply.isDuplicate());
        assertFalse(reply.isCreated());
        assertEquals("task-1", reply.getTaskId());
        assertEquals(11L, reply.getUserMessageId());
        assertEquals(12L, reply.getAssistantMessageId());
        assertEquals("RUNNING", reply.getAssistantStatus());
        verify(messageMapper, never()).insert(any(ConversationMessage.class));
        verify(conversationMapper, never()).incrMessageCount(anyString(), anyInt());
    }

    @Test
    void shouldRejectSameMessageUuidWithDifferentContent() {
        when(messageMapper.selectByMessageUuid(CONVERSATION, MESSAGE))
                .thenReturn(userMessage("你好", "task-1"));

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.openReply(CONVERSATION, MESSAGE, null, USER_ID,
                        "换了一句话", null, "trace-1", "task-new"));

        assertEquals(409, error.getStatus().value());
        assertEquals("MESSAGE_CONTENT_CONFLICT", error.getCode());
        verify(messageMapper, never()).insert(any(ConversationMessage.class));
    }

    @Test
    void shouldRejectConversationOwnedByAnotherUser() {
        Conversation other = new Conversation();
        other.setId(100L);
        other.setConversationId(CONVERSATION);
        other.setUserId(99L);
        other.setStatus(Conversation.STATUS_ACTIVE);
        when(conversationMapper.selectByConversationId(CONVERSATION)).thenReturn(other);

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.openReply(CONVERSATION, MESSAGE, null, USER_ID,
                        "你好", null, "trace-1", "task-new"));

        assertEquals(403, error.getStatus().value());
        assertEquals("CONVERSATION_FORBIDDEN", error.getCode());
    }

    @Test
    void shouldRejectMissingConversationWhenNotCreatingDraft() {
        when(conversationMapper.selectByConversationId(CONVERSATION)).thenReturn(null);
        when(messageMapper.countByConversationId(CONVERSATION, USER_ID)).thenReturn(0);

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.openReply(CONVERSATION, MESSAGE, null, USER_ID,
                        "你好", null, "trace-1", "task-new"));

        assertEquals(404, error.getStatus().value());
        assertEquals("CONVERSATION_NOT_FOUND", error.getCode());
        verify(conversationMapper, never()).selectByConversationIdForUpdate(anyString());
    }

    @Test
    void shouldInsertUserMessageWithClientMessageUuidAsPublicId() {
        when(messageMapper.selectByMessageUuid(CONVERSATION, MESSAGE)).thenReturn(null);
        when(messageMapper.selectTailSequenceNoForUpdate(CONVERSATION)).thenReturn(0);
        AtomicLong ids = new AtomicLong(10L);
        when(messageMapper.insert(any(ConversationMessage.class))).thenAnswer(invocation -> {
            ConversationMessage message = invocation.getArgument(0);
            message.setId(ids.incrementAndGet());
            return 1;
        });

        ConversationMessageService.PendingReply reply = service.openReply(
                CONVERSATION, MESSAGE, null, USER_ID, "你好", null, "trace-1", "task-1");

        assertFalse(reply.isDuplicate());
        assertEquals(11L, reply.getUserMessageId());
        assertEquals(12L, reply.getAssistantMessageId());

        ArgumentCaptor<ConversationMessage> inserted =
                ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messageMapper, org.mockito.Mockito.times(2)).insert(inserted.capture());
        List<ConversationMessage> rows = inserted.getAllValues();
        assertEquals(MESSAGE, rows.get(0).getPublicId());
        assertEquals("user", rows.get(0).getRole());
        assertEquals("你好", rows.get(0).getContent());
        // assistant 的 UUID 由服务端生成，不能复用用户消息的 message_uuid
        assertNotNull(rows.get(1).getPublicId());
        assertNotEquals(MESSAGE, rows.get(1).getPublicId());
        assertEquals("assistant", rows.get(1).getRole());
        assertEquals(ConversationMessage.STATUS_RUNNING, rows.get(1).getStatus());
        verify(conversationMapper).incrMessageCount(eq(CONVERSATION), eq(2));
    }

    private ConversationMessage userMessage(String content, String taskId) {
        ConversationMessage message = new ConversationMessage();
        message.setId(11L);
        message.setConversationId(CONVERSATION);
        message.setUserId(USER_ID);
        message.setRole(ConversationMessageService.ROLE_USER);
        message.setContent(content);
        message.setPublicId(MESSAGE);
        message.setSequenceNo(1);
        message.setTaskId(taskId);
        message.setStatus(ConversationMessage.STATUS_COMPLETED);
        return message;
    }

    private ConversationMessage assistantMessage(String taskId) {
        ConversationMessage message = new ConversationMessage();
        message.setId(12L);
        message.setConversationId(CONVERSATION);
        message.setUserId(USER_ID);
        message.setRole(ConversationMessageService.ROLE_ASSISTANT);
        message.setContent("");
        message.setSequenceNo(2);
        message.setTaskId(taskId);
        message.setStatus(ConversationMessage.STATUS_RUNNING);
        return message;
    }
}
