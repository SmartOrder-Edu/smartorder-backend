package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.mapper.AgentTraceEventMapper;
import com.smartorder.edu.agent.model.entity.AgentTraceEvent;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.common.model.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/agent")
@Tag(name = "Agent 执行追踪", description = "查询 Agent 执行追踪事件")
public class AgentTraceController {

    private final AgentTraceEventMapper traceEventMapper;

    @GetMapping("/trace/{traceId}")
    @Operation(summary = "获取追踪事件", description = "根据 traceId 返回该次 Agent 执行的全部追踪事件，按顺序排列")
    public Result<List<AgentTraceEvent>> getTraceEvents(@PathVariable String traceId) {
        List<AgentTraceEvent> events = traceEventMapper.selectByTraceId(traceId);
        log.info("[TraceController] 查询 trace: traceId={}, eventCount={}", traceId, events.size());
        return Result.success(events);
    }
}
