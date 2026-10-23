package com.smartorder.edu.agent.controller;

import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.impl.PlanTodayScheduleTool;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 今日任务日程编排入口。
 *
 * <p>该接口面向任务日程页的显式用户操作：用户点击生成后，后端同步完成
 * 最近学习情况分析、任务创建和可解释结果返回。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/agent/daily-schedule")
@Tag(name = "Agent 今日任务编排", description = "根据最近学习情况生成并解释今日任务日程")
public class AgentDailyScheduleController {

    private final PlanTodayScheduleTool planTodayScheduleTool;

    @PostMapping("/plan-today")
    @Operation(summary = "Agent 生成今日任务", description = "结合最近学习记录、未完成任务和学习路径，生成今日任务并返回编排依据")
    public Result<Object> planToday(@RequestBody(required = false) PlanTodayScheduleTool.PlanTodayScheduleRequest request) {
        Long userId = getCurrentUserId();
        ToolInvokeContext context = ToolInvokeContext.builder()
                .userId(userId)
                .agentName("DailyScheduleAgent")
                .confirmationGranted(true)
                .build();
        Object result = planTodayScheduleTool.execute(request, context);
        log.info("[AgentDailySchedule] generated today schedule, userId={}", userId);
        return Result.success(result);
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
