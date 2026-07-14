package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.algorithm.LearningAlgorithmQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/algorithm")
@Tag(name = "学习算法", description = "学习状态、薄弱点和算法解释查询接口")
public class LearningAlgorithmController {

    private final LearningAlgorithmQueryService queryService;

    @GetMapping("/learning-state")
    @Operation(summary = "查询学习状态", description = "返回指定学习空间内的知识点掌握度、置信度、薄弱点和算法解释事实")
    public Result<Map<String, Object>> getLearningState(@RequestParam Long spaceId) throws Exception {
        Long userId = ThreadLocalUtils.getCurrentUserId();
        return Result.success(queryService.getLearningState(userId, spaceId));
    }
}
