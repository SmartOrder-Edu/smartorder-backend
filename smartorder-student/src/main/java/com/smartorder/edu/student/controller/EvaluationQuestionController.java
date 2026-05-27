package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.vo.GenerateQuestionVO;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/evaluation/questions")
@Tag(name = "测评出题", description = "测评题目的生成接口")

public class EvaluationQuestionController {

    private final IEvaluationQuestionService evaluationQuestionService;

    @PostMapping("/generate")
    @Operation(summary = "生成测评题目", description = "根据课程、知识点、题型和难度生成题目并保存到题库")
    public Result<GenerateQuestionVO> generateQuestions(@Validated @RequestBody GenerateQuestionDTO dto) {
        List<EvaluationQuestion> questions = evaluationQuestionService.generateQuestions(dto);
        return Result.success(GenerateQuestionVO.from(dto, questions), "题目生成成功");
    }
}
