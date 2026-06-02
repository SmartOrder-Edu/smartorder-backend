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

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/evaluation/questions")
@Tag(name = "测评出题", description = "测评题目的生成接口")
public class EvaluationQuestionController {

    private final IEvaluationQuestionService evaluationQuestionService;

    @GetMapping
    @Operation(summary = "查询测评题库", description = "按学习空间查询已保存的测评题目，供前端联调展示")
    public Result<List<GenerateQuestionVO.QuestionVO>> listQuestions(
            @RequestParam(required = false) Long learningSpaceId,
            @RequestParam(required = false) Integer limit) {
        return Result.success(evaluationQuestionService.listQuestions(learningSpaceId, limit));
    }

    // 工业级规范：显式指定 produces 为 text/event-stream
    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "生成测评题目", description = "根据课程、知识点、题型和难度生成题目并保存到题库")
    public SseEmitter generateQuestions(@Validated @RequestBody GenerateQuestionDTO dto) {
        // 设置长超时，因为 Agent 思考可能很久----30 minute
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);

        emitter.onCompletion(emitter::complete);
        emitter.onTimeout(emitter::complete);

        evaluationQuestionService.generateQuestions(dto,emitter);

        return emitter;
    }
}
