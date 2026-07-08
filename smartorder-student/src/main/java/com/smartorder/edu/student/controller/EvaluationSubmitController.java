package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.vo.TaskSubmitResultVO;
import com.smartorder.edu.student.service.IEvaluationSubmitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 测评答案提交与判分接口层
 *
 * <p>提供直接提交答案的端点，适用于日常练习测评场景（无需预先创建 session）。
 * 客观题程序判分，主观题异步 AI 判分后通过轮询获取最终结果。</p>
 *
 * @author 马强
 * @since 2026-07-07
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/evaluation")
@Tag(name = "测评提交判分", description = "测评答案提交与判分接口")
public class EvaluationSubmitController {

    private final IEvaluationSubmitService submitService;

    /**
     * 提交答案并获取即时判分结果。
     * <p>客观题即时返回判分结果，主观题标记为 pending_review 并在后台异步判分。</p>
     */
    @PostMapping("/submit")
    @Operation(summary = "提交测评答案", description = "提交用户的答题答案，客观题程序判分，主观题异步AI判分")
    public Result<TaskSubmitResultVO> submit(
            @RequestParam Long taskId, @RequestParam Long spaceId,
            @Validated @RequestBody SubmitAnswersDTO dto) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        log.info("提交测评答案: taskId={}, spaceId={}, userId={}, answerCount={}",
                taskId, spaceId, userId, dto.getAnswers().size());
        TaskSubmitResultVO result = submitService.submit(taskId, spaceId, userId, dto);
        return Result.success(result);
    }

    /**
     * 查询判分结果（用于主观题轮询）。
     */
    @GetMapping("/result")
    @Operation(summary = "查询判分结果", description = "按任务ID查询最新判分结果，用于主观题批阅完成后的轮询")
    public Result<TaskSubmitResultVO> getResult(@RequestParam Long taskId) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        TaskSubmitResultVO result = submitService.getResult(taskId, userId);
        return Result.success(result);
    }


}
