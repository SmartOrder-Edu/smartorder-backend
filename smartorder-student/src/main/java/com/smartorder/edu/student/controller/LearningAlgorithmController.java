package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.algorithm.LearningAlgorithmQueryService;
import com.smartorder.edu.student.domain.dto.QuestionKnowledgeMappingSaveDTO;
import com.smartorder.edu.student.domain.vo.QuestionKnowledgeMappingVO;
import com.smartorder.edu.student.service.IQuestionKnowledgeMappingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/algorithm")
@Tag(name = "学习算法", description = "学习状态、薄弱点和算法解释查询接口")
public class LearningAlgorithmController {

    private final LearningAlgorithmQueryService queryService;
    private final IQuestionKnowledgeMappingService mappingService;

    @GetMapping("/learning-state")
    @Operation(summary = "查询学习状态", description = "返回指定学习空间内的知识点掌握度、置信度、薄弱点和算法解释事实")
    public Result<Map<String, Object>> getLearningState(@RequestParam Long spaceId) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        return Result.success(queryService.getLearningState(userId, spaceId));
    }

    @GetMapping("/questions/{questionId}/knowledge-mappings")
    @Operation(summary = "查询题目知识点映射", description = "返回题目当前知识点映射，以及题目所属学习空间下可选知识点候选")
    public Result<QuestionKnowledgeMappingVO> getQuestionKnowledgeMappings(@PathVariable Long questionId) {
        return Result.success(mappingService.getMappings(questionId));
    }

    @PutMapping("/questions/{questionId}/knowledge-mappings")
    @Operation(summary = "保存题目知识点映射", description = "替换保存题目到知识点的人工映射；权重会自动归一化，作为后续掌握度证据分摊依据")
    public Result<QuestionKnowledgeMappingVO> saveQuestionKnowledgeMappings(
            @PathVariable Long questionId,
            @Valid @RequestBody QuestionKnowledgeMappingSaveDTO dto) {
        return Result.success(mappingService.saveMappings(questionId, dto));
    }
}
