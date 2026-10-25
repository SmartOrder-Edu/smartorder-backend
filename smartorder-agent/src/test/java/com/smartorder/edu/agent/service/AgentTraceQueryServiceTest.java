package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import com.smartorder.edu.agent.model.vo.AgentTraceEventBriefVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTraceQueryServiceTest {

    private final AgentTraceEventMapper mapper = mock(AgentTraceEventMapper.class);

    private final AgentTraceQueryService service = new AgentTraceQueryService(mapper);

    @Test
    void shouldGroupByTraceIdAndConvertToBriefDto() {
        when(mapper.selectByTraceIds(7L, List.of("t1", "t2", "t3")))
                .thenReturn(List.of(
                        event("t1", "agent.tool.started", 1, "{\"toolName\":\"webSearch\"}"),
                        event("t1", "agent.tool.completed", 2, "{\"toolName\":\"webSearch\"}"),
                        event("t2", "agent.text.delta", 1, "{\"text\":\"你好\"}")
                ));

        Map<String, List<AgentTraceEventBriefVO>> result = service.query(List.of("t1", "t2", "t3"), 7L);

        assertEquals(3, result.size(), "请求的每个 traceId 都应有条目，包括没有事件的 t3");
        assertEquals(2, result.get("t1").size());
        assertEquals(1, result.get("t2").size());
        assertTrue(result.get("t3").isEmpty());

        AgentTraceEventBriefVO first = result.get("t1").get(0);
        assertEquals("t1", first.getTraceId());
        assertEquals("agent.tool.started", first.getEventType());
        assertEquals(1, first.getSeq());
        assertNotNull(first.getTimestamp());
        assertEquals("{\"toolName\":\"webSearch\"}", first.getPayload());
    }

    @Test
    void shouldNormalizeDuplicatesBlanksAndKeepOrder() {
        assertEquals(List.of("t1", "t2"),
                AgentTraceQueryService.normalizeTraceIds(List.of(" t1 ", "", "t2", "t1", "  ")));
        assertEquals(List.of(), AgentTraceQueryService.normalizeTraceIds(null));
    }

    @Test
    void shouldReturnEmptyForMissingUserOrEmptyIds() {
        assertTrue(service.query(List.of("t1"), null).isEmpty());
        assertTrue(service.query(List.of(), 7L).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void shouldQueryWithNormalizedIds() {
        when(mapper.selectByTraceIds(7L, List.of("t1", "t2"))).thenReturn(List.of());

        service.query(List.of("t1", "t1", "t2"), 7L);

        verify(mapper).selectByTraceIds(7L, List.of("t1", "t2"));
    }

    private AgentTraceEvent event(String traceId, String eventType, int seq, String payload) {
        AgentTraceEvent event = new AgentTraceEvent();
        event.setTraceId(traceId);
        event.setEventType(eventType);
        event.setSequenceNum(seq);
        event.setPayload(payload);
        event.setCreateTime(LocalDateTime.of(2026, 10, 25, 12, 0, seq));
        return event;
    }
}
