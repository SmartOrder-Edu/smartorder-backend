package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import com.smartorder.edu.agent.model.vo.AgentTraceEventBriefVO;
import com.smartorder.edu.agent.service.AgentTraceQueryService;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 执行追踪", description = "查询 Agent 执行追踪事件")
public class AgentTraceController {

    private final AgentTraceEventMapper traceEventMapper;

    private final AgentTraceQueryService traceQueryService;

    @GetMapping("/trace/{traceId}")
    @Operation(summary = "获取追踪事件", description = "根据 traceId 返回该次 Agent 执行的全部追踪事件，按顺序排列")
    public Result<List<AgentTraceEvent>> getTraceEvents(@PathVariable String traceId) {
        List<AgentTraceEvent> events = traceEventMapper.selectByTraceId(traceId);
        log.info("[TraceController] 查询 trace: traceId={}, eventCount={}", traceId, events.size());
        return Result.success(events);
    }

    @GetMapping(value = "/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "批量获取追踪事件",
            description = "一次最多 50 个 traceId，按当前用户过滤；返回 traceId → 精简事件列表，供历史消息首屏一次性加载")
    public Result<Map<String, List<AgentTraceEventBriefVO>>> getTraceEventsBatch(
            @RequestParam(value = "traceIds", required = false) List<String> traceIds) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        List<String> normalized = AgentTraceQueryService.normalizeTraceIds(traceIds);
        if (normalized.size() > AgentTraceQueryService.MAX_TRACE_IDS) {
            return Result.fail(ResultCode.VALIDATION_ERROR,
                    "traceIds 最多 " + AgentTraceQueryService.MAX_TRACE_IDS + " 个");
        }
        return Result.success(traceQueryService.query(normalized, userId));
    }

    private Long currentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims == null ? null : claims.get("userId", Long.class);
    }
}
