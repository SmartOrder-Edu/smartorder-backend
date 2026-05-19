package com.smartorder.edu.learning.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.learning.domain.entity.LearningPathEdge;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.service.ILearningPathEdgeService;
import com.smartorder.edu.learning.service.ILearningPathNodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习路径控制器
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/learning-path")
@Tag(name = "学习路径管理", description = "学习路径节点的查询接口")
public class LearningPathController {

    private final ILearningPathNodeService pathNodeService;
    private final ILearningPathEdgeService pathEdgeService;

    @GetMapping
    @Operation(summary = "获取学习空间的学习路径", description = "返回指定学习空间的所有路径节点和边")
    public Result<Map<String, Object>> getPath(@RequestParam Long spaceId) {
        List<LearningPathNode> nodes = pathNodeService.getBySpaceId(spaceId);
        List<LearningPathEdge> edges = pathEdgeService.getBySpaceId(spaceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spaceId", spaceId);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return Result.success(result);
    }
}
