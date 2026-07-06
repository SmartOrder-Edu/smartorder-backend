package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.vo.GenerateQuestionVO;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 测评出题接口层
 *
 * <p>对外暴露题库的查询与生成能力。所有接口统一挂在 {@code /evaluation/questions} 路径下，
 * 与需求中的 {@code evaluation/questions/generate} 生成路径对应。</p>
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/evaluation/questions")
@Tag(name = "测评出题", description = "测评题目的生成接口")
public class EvaluationQuestionController {

    /** 测评出题业务逻辑 */
    private final IEvaluationQuestionService evaluationQuestionService;

    /**
     * 查询已保存的测评题库。
     *
     * @param learningSpaceId 学习空间ID（可选，用于按学习空间过滤题目）
     * @param limit           返回条数（可选，未传默认30，范围1~100）
     * @return 题目视图对象列表
     */
    @GetMapping
    @Operation(summary = "查询测评题库", description = "按学习空间查询已保存的测评题目，供前端联调展示")
    public Result<List<GenerateQuestionVO.QuestionVO>> listQuestions(
            @RequestParam(required = false) Long learningSpaceId,
            @RequestParam(required = false) Integer limit) {
        return Result.success(evaluationQuestionService.listQuestions(learningSpaceId, limit));
    }

    /**
     * 生成测评题目（核心入口）。
     *
     * <p>该接口为 SSE（Server-Sent Events）流式接口，适合大模型 Agent 思考耗时较长的场景，
     * 可实时将题目生成进度与流式文本推送至前端。生成逻辑由 {@code IEvaluationQuestionService}
     * 在异步线程中执行，接口方法本身立即返回 {@link SseEmitter}。</p>
     *
     * @param dto 出题参数：课程、知识点、主题、各题型数量/难度等
     * @return SSE 发射器，用于向前端流式推送生成事件
     */
    // 工业级规范：显式指定 produces 为 text/event-stream
    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "生成测评题目", description = "根据课程、知识点、题型和难度生成题目并保存到题库")
    public SseEmitter generateQuestions(@Validated @RequestBody GenerateQuestionDTO dto) {
        // 设置长超时（30分钟），因为大模型 Agent 思考可能很久
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        // 正常完成或超时后均关闭 SSE 连接，避免资源泄漏
        emitter.onCompletion(emitter::complete);
        emitter.onTimeout(emitter::complete);

        // 提交生成任务到异步线程，接口立即返回，后续通过 emitter 推送事件
        evaluationQuestionService.generateQuestions(dto, emitter);

        return emitter;
    }
}
