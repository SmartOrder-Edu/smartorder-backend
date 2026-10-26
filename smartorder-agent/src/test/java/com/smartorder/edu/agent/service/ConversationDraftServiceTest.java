package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.exception.AgentTaskException;
import com.smartorder.edu.agent.mapper.AgentTaskRecordMapper;
import com.smartorder.edu.agent.mapper.ConversationDraftMapper;
import com.smartorder.edu.agent.model.entity.AgentTaskRecord;
import com.smartorder.edu.agent.model.entity.ConversationDraft;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 鑽夌缁戝畾涓?requestId/draftId 涓€鑷存€э細鍐茬獊蹇呴』 409锛屼笉鑳芥倓鎮勫鐢ㄦ棫浼氳瘽銆? */
class ConversationDraftServiceTest {

    private static final String DRAFT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String OTHER_DRAFT = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String CONVERSATION = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    private static final String OTHER_CONVERSATION = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    private static final Long USER_ID = 7L;

    private ConversationDraftMapper draftMapper;
    private AgentTaskRecordMapper taskMapper;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ConversationDraftService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        draftMapper = mock(ConversationDraftMapper.class);
        taskMapper = mock(AgentTaskRecordMapper.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lenientGet(null);
        service = new ConversationDraftService(draftMapper, taskMapper, redis);
    }

    @Test
    void shouldRejectNewDraftReusingExistingConversationUuid() {
        ConversationDraft bound = row(OTHER_DRAFT, CONVERSATION, USER_ID);
        when(draftMapper.selectByDraftIdForUpdate(DRAFT)).thenReturn(null);
        when(draftMapper.selectByConversationUuidForUpdate(CONVERSATION)).thenReturn(bound);

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.bindOrAssert(DRAFT, CONVERSATION, USER_ID));

        assertEquals(409, error.getStatus().value());
        assertEquals("CONVERSATION_DRAFT_CONFLICT", error.getCode());
        verify(draftMapper, never()).insert(any(ConversationDraft.class));
    }

    @Test
    void shouldRejectSameDraftSwitchingConversationUuid() {
        when(draftMapper.selectByDraftIdForUpdate(DRAFT))
                .thenReturn(row(DRAFT, OTHER_CONVERSATION, USER_ID));
        when(draftMapper.selectByConversationUuidForUpdate(CONVERSATION)).thenReturn(null);

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.bindOrAssert(DRAFT, CONVERSATION, USER_ID));

        assertEquals("DRAFT_CONVERSATION_CONFLICT", error.getCode());
    }

    @Test
    void shouldRejectConversationOwnedByAnotherUser() {
        when(draftMapper.selectByDraftIdForUpdate(DRAFT)).thenReturn(row(DRAFT, CONVERSATION, 99L));

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.bindOrAssert(DRAFT, CONVERSATION, USER_ID));

        assertEquals(403, error.getStatus().value());
    }

    @Test
    void shouldCreateBindingAndCacheBothDirections() {
        when(draftMapper.selectByDraftIdForUpdate(DRAFT)).thenReturn(null);
        when(draftMapper.selectByConversationUuidForUpdate(CONVERSATION)).thenReturn(null);

        assertDoesNotThrow(() -> service.bindOrAssert(DRAFT, CONVERSATION, USER_ID));

        verify(draftMapper).insert(any(ConversationDraft.class));
        verify(valueOps).set(eq("agent:draft:conv:" + DRAFT),
                eq(USER_ID + ":" + CONVERSATION), any(Duration.class));
        verify(valueOps).set(eq("agent:conv:draft:" + CONVERSATION),
                eq(USER_ID + ":" + DRAFT), any(Duration.class));
    }

    @Test
    void shouldSkipMysqlWhenBothCacheDirectionsMatch() {
        when(valueOps.get("agent:draft:conv:" + DRAFT)).thenReturn(USER_ID + ":" + CONVERSATION);
        when(valueOps.get("agent:conv:draft:" + CONVERSATION)).thenReturn(USER_ID + ":" + DRAFT);

        service.bindOrAssert(DRAFT, CONVERSATION, USER_ID);

        verify(draftMapper, never()).selectByDraftIdForUpdate(anyString());
        verify(draftMapper, never()).insert(any(ConversationDraft.class));
    }

    @Test
    void shouldNotTrustCacheWrittenForAnotherUser() {
        when(valueOps.get("agent:draft:conv:" + DRAFT)).thenReturn("99:" + CONVERSATION);
        when(valueOps.get("agent:conv:draft:" + CONVERSATION)).thenReturn("99:" + DRAFT);
        when(draftMapper.selectByDraftIdForUpdate(DRAFT))
                .thenReturn(row(DRAFT, CONVERSATION, 99L));

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.bindOrAssert(DRAFT, CONVERSATION, USER_ID));

        assertEquals(403, error.getStatus().value());
    }

    @Test
    void shouldRejectSameRequestIdWithDifferentDraftId() {
        when(valueOps.get("agent:req:draft:" + USER_ID + ":req-1")).thenReturn(OTHER_DRAFT);

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.assertRequestDraft(USER_ID, "req-1", DRAFT));

        assertEquals(409, error.getStatus().value());
        assertEquals("REQUEST_DRAFT_CONFLICT", error.getCode());
    }

    @Test
    void shouldRejectRequestDraftMismatchFromMysqlWhenCacheMisses() {
        when(valueOps.get("agent:req:draft:" + USER_ID + ":req-1")).thenReturn(null);
        AgentTaskRecord record = new AgentTaskRecord();
        record.setTaskId("task-1");
        record.setDraftId(OTHER_DRAFT);
        when(taskMapper.selectByClientRequest(USER_ID, "req-1")).thenReturn(record);

        AgentTaskException error = assertThrows(AgentTaskException.class,
                () -> service.assertRequestDraft(USER_ID, "req-1", DRAFT));

        assertEquals("REQUEST_DRAFT_CONFLICT", error.getCode());
    }

    private ConversationDraft row(String draftId, String conversationUuid, Long userId) {
        ConversationDraft row = new ConversationDraft();
        row.setDraftId(draftId);
        row.setConversationUuid(conversationUuid);
        row.setUserId(userId);
        return row;
    }

    private void lenientGet(String value) {
        when(valueOps.get(anyString())).thenReturn(value);
    }
}
