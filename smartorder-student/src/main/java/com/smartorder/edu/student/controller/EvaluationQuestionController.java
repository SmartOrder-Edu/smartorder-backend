package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.vo.EvaluationQuestionVO;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.vo.GenerateQuestionVO;
import com.smartorder.edu.student.domain.vo.SessionResultVO;
import com.smartorder.edu.student.domain.vo.SessionReportVO;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import com.smartorder.edu.student.service.IEvaluationSessionService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 测评接口层
 *
 * <p>统一管理出题、答题、判分、报告等测评全流程接口，所有接口挂在 {@code /evaluation} 路径下。</p>
 *
 * @author 马强
 * @since 2026-07-07
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/evaluation")
@Tag(name = "测评中心", description = "测评出题、答题、判分与报告接口")
public class EvaluationQuestionController {

    private final IEvaluationQuestionService evaluationQuestionService;
    private final IEvaluationSessionService sessionService;

    // ======================== 出题相关 ========================

    @GetMapping("/questions/task")
    @Operation(summary = "查询测评任务列表", description = "按学习空间查询测评会话（Session）列表，前端根据 status 判断是否已完成")
    public Result<List<EvaluationSession>> listQuestionsTaskList(
            @RequestParam(required = false) Long learningSpaceId
    ) throws Exception {
        log.info("查询测评任务列表: learningSpaceId={}", learningSpaceId);
        Long currentUserId = getCurrentUserId();
        return Result.success(evaluationQuestionService.listQuestionsTaskList(learningSpaceId, currentUserId));
    }

    /**
     * 查询已保存的测评题库。
     */
    @GetMapping("/questions")
    @Operation(summary = "查询测评题库题目列表", description = "按学习空间或任务ID查询已保存的测评题目，返回完整题目视图（含题干、选项、答案、解析、常见错误等）")
    public Result<List<EvaluationQuestionVO>> listQuestions(
            @RequestParam(required = false) Long learningSpaceId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long taskId) {
        log.info("查询测评题库题目列表: learningSpaceId={}, limit={}, taskId={}", learningSpaceId, limit, taskId);
        return Result.success(evaluationQuestionService.listQuestions(learningSpaceId, limit, taskId));
    }

    /**
     * 生成测评题目（核心入口 - SSE 流式）。
     */
    @PostMapping(value = "/questions/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "生成测评题目", description = "根据课程、知识点、题型和难度生成题目并保存到题库")
    public SseEmitter generateQuestions(@Validated @RequestBody GenerateQuestionDTO dto) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        emitter.onCompletion(() -> log.info("SSE completed"));
        emitter.onTimeout(() -> {
            log.warn("SSE timeout");
            emitter.complete();
        });
        emitter.onError((ex) -> {
            log.error("SSE error: {}", ex.getMessage(), ex);
            emitter.completeWithError(ex);
        });

        evaluationQuestionService.generateQuestions(dto, emitter);
        return emitter;
    }

    // ======================== Session 答题相关 ========================

    /**
     * 获取试卷题目列表。
     */
    @GetMapping("/session/{id}/questions")
    @Operation(summary = "获取试卷题目列表", description = "根据测评会话ID获取该试卷的所有题目")
    public Result<List<EvaluationQuestionVO>> getSessionQuestions(@PathVariable Long id) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        return Result.success(sessionService.getSessionQuestions(id, userId));
    }

    /**
     * 开始答题。
     */
    @PutMapping("/session/{id}/start")
    @Operation(summary = "开始答题", description = "将测评任务状态从 created 改为 doing，记录开始时间")
    public Result<Void> startSession(@PathVariable Long id) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        sessionService.startSession(id, userId);
        return Result.success("开始答题");
    }

    /**
     * 提交全部答案。
     * <p>客观题即时返回判分结果，主观题标记为 pending_review 并在后台异步判分。</p>
     */
    @PostMapping("/session/{id}/submit")
    @Operation(summary = "提交测评答案", description = "批量提交用户答案，客观题程序判分，主观题异步AI判分，同时更新session总分/实际得分/正确率")
    public Result<SessionResultVO> submitAnswers(@PathVariable Long id,
                                                  @Validated @RequestBody SubmitAnswersDTO dto) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        log.info("提交测评答案: sessionId={}, userId={}, answerCount={}",
                id, userId, dto.getAnswers().size());
        SessionResultVO result = sessionService.submitAnswers(id, userId, dto);
        return Result.success(result);
    }

    /**
     * 查询判分结果（用于主观题轮询）。
     */
    @GetMapping("/session/{id}/result")
    @Operation(summary = "查询判分结果", description = "查询最新判分结果，用于主观题批阅完成后的轮询")
    public Result<SessionResultVO> getSessionResult(@PathVariable Long id) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        return Result.success(sessionService.getSessionResult(id, userId));
    }

    /**
     * 查询学习报告。
     */
    @GetMapping("/session/{id}/report")
    @Operation(summary = "查询学习报告", description = "查询测评学习报告，包含知识点掌握度、薄弱点、优势点分析")
    public Result<SessionReportVO> getSessionReport(@PathVariable Long id) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        return Result.success(sessionService.getSessionReport(id, userId));
    }

    // ======================== 工具方法 ========================

    public Long getCurrentUserId() throws Exception {
        Claims claims = ThreadLocalUtils.get();
        if (claims == null) throw new Exception("未登录");
        Object userId = claims.get("userId");
        if (userId == null) throw new Exception("用户信息缺失");
        return (Long) userId;
    }
}
