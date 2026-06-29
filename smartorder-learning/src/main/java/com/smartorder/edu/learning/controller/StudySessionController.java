package com.smartorder.edu.learning.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.learning.domain.vo.StudyEventReceiptVO;
import com.smartorder.edu.learning.domain.vo.StudySessionCompleteVO;
import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.dto.StudyEventDTO;
import com.smartorder.edu.learning.dto.StudySessionCompleteDTO;
import com.smartorder.edu.learning.dto.StudySessionStartDTO;
import com.smartorder.edu.learning.dto.StudyTeachingActionStatusDTO;
import com.smartorder.edu.learning.service.IStudySessionService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/study-sessions")
@Tag(name = "学习会话", description = "Agent 驱动学习会话接口")
public class StudySessionController {

    private final IStudySessionService studySessionService;

    @PostMapping
    @Operation(summary = "创建或启动学习会话", description = "返回脚本步骤、当前场景和可播放 Timeline")
    public Result<StudySessionVO> start(@RequestBody StudySessionStartDTO dto) {
        Long userId = getCurrentUserId();
        return Result.success(studySessionService.startSession(userId, dto));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "获取学习会话状态", description = "返回指定学习会话的当前状态和备课包数据")
    public Result<StudySessionVO> detail(@PathVariable String sessionId) {
        Long userId = getCurrentUserId();
        return Result.success(studySessionService.getSession(userId, sessionId));
    }

    @PostMapping("/{sessionId}/events")
    @Operation(summary = "上报学习事件", description = "记录学生在学习会话中的播放、答题、反馈和步骤行为")
    public Result<StudyEventReceiptVO> reportEvent(
            @PathVariable String sessionId,
            @RequestBody StudyEventDTO dto
    ) {
        Long userId = getCurrentUserId();
        return Result.success(studySessionService.recordEvent(userId, sessionId, dto));
    }

    @PostMapping("/{sessionId}/complete")
    @Operation(summary = "完成学习会话", description = "保存会话总结，汇总学习记录，并同步真实任务完成状态")
    public Result<StudySessionCompleteVO> complete(
            @PathVariable String sessionId,
            @RequestBody(required = false) StudySessionCompleteDTO dto
    ) {
        Long userId = getCurrentUserId();
        return Result.success(studySessionService.completeSession(
                userId, sessionId, dto == null ? new StudySessionCompleteDTO() : dto));
    }

    @PatchMapping("/{sessionId}/actions/{actionId}/status")
    @Operation(summary = "回写教学动作状态", description = "前端应用教学动作后回写 applied/failed 状态，便于追踪教学动作是否真正执行")
    public Result<Void> updateActionStatus(
            @PathVariable String sessionId,
            @PathVariable String actionId,
            @RequestBody StudyTeachingActionStatusDTO dto
    ) {
        Long userId = getCurrentUserId();
        studySessionService.updateTeachingActionStatus(userId, sessionId, actionId, dto);
        return Result.success(null);
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
