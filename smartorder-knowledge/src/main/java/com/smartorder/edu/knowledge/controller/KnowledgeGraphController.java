package com.smartorder.edu.knowledge.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.service.IKnowledgeGraphService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱控制器
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/knowledge-graph")
@Tag(name = "知识图谱管理", description = "知识图谱节点的查询接口")
public class KnowledgeGraphController {

    private final IKnowledgeGraphService knowledgeGraphService;

    @GetMapping
    @Operation(summary = "获取学习空间的知识图谱", description = "返回指定学习空间的所有节点和边")
    public Result<Map<String, Object>> getGraph(@RequestParam Long spaceId) {
        List<KnowledgeGraphNode> nodes = knowledgeGraphService.getNodesBySpaceId(spaceId);
        List<KnowledgeGraphEdge> edges = knowledgeGraphService.getEdgesBySpaceId(spaceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spaceId", spaceId);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return Result.success(result);
    }

    @GetMapping("/search")
    @Operation(summary = "搜索知识图谱节点", description = "按关键字搜索指定学习空间的知识节点")
    public Result<List<KnowledgeGraphNode>> search(@RequestParam Long spaceId, @RequestParam String keyword) {
        List<KnowledgeGraphNode> nodes = knowledgeGraphService.searchNodes(spaceId, keyword);
        return Result.success(nodes);
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
