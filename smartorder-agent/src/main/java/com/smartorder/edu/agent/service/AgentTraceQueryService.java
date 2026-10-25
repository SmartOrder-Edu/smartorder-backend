package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import com.smartorder.edu.agent.model.vo.AgentTraceEventBriefVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 批量 trace 查询：一次 IN 查询 + 一次鉴权，替代前端逐条 trace 的 N+1 请求。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceQueryService {

    /** 单次请求最多查询 50 个 trace（前端超过时分批） */
    public static final int MAX_TRACE_IDS = 50;

    private final AgentTraceEventMapper traceEventMapper;

    /**
     * 去空、去重并保持请求顺序。
     */
    public static List<String> normalizeTraceIds(List<String> traceIds) {
        if (traceIds == null || traceIds.isEmpty()) {
            return List.of();
        }
        return traceIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * 按 traceId 分组返回精简事件。
     *
     * <p>结果里会为每个请求的 traceId 保留一个（可能为空的）列表，
     * 前端不需要再区分“没有这个 key”和“没有事件”。</p>
     */
    public Map<String, List<AgentTraceEventBriefVO>> query(List<String> traceIds, Long userId) {
        if (userId == null) {
            return Map.of();
        }
        List<String> normalized = normalizeTraceIds(traceIds);
        if (normalized.isEmpty()) {
            return Map.of();
        }

        List<AgentTraceEvent> events = traceEventMapper.selectByTraceIds(userId, normalized);
        Map<String, List<AgentTraceEventBriefVO>> grouped = new LinkedHashMap<>();
        for (String traceId : normalized) {
            grouped.put(traceId, new ArrayList<>());
        }
        if (events != null) {
            for (AgentTraceEvent event : events) {
                List<AgentTraceEventBriefVO> list = grouped.get(event.getTraceId());
                // SQL 已按 user_id + trace_id IN 过滤；这里再忽略意外返回的其它 trace
                if (list == null) {
                    continue;
                }
                list.add(toBrief(event));
            }
        }
        log.debug("[TraceQuery] 批量查询: userId={}, traceCount={}, eventCount={}",
                userId, normalized.size(), events == null ? 0 : events.size());
        return grouped;
    }

    private AgentTraceEventBriefVO toBrief(AgentTraceEvent event) {
        Long timestamp = event.getCreateTime() == null ? null
                : event.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return AgentTraceEventBriefVO.builder()
                .traceId(event.getTraceId())
                .eventType(event.getEventType())
                .seq(event.getSequenceNum() == null ? 0 : event.getSequenceNum())
                .timestamp(timestamp)
                .payload(event.getPayload())
                .build();
    }
}
