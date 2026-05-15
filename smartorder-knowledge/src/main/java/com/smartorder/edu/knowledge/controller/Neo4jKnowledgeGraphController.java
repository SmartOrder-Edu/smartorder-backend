package com.smartorder.edu.knowledge.controller;

import com.smartorder.edu.knowledge.domain.entity.Neo4jKnowledgeNode;
import com.smartorder.edu.knowledge.service.INeo4jKnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Neo4j 知识图谱控制器
 */
@RestController
@RequestMapping("/api/v1/neo4j")
@RequiredArgsConstructor
public class Neo4jKnowledgeGraphController {

    private final INeo4jKnowledgeGraphService neo4jService;

    /**
     * 测试连接
     */
    @GetMapping("/test")
    public ResponseEntity<String> testConnection() {
        try {
            List<Neo4jKnowledgeNode> nodes = neo4jService.findNodesBySpaceId(1L);
            return ResponseEntity.ok("Neo4j连接成功，节点数量: " + nodes.size());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Neo4j连接失败: " + e.getMessage());
        }
    }

    /**
     * 根据空间ID查找所有节点
     */
    @GetMapping("/nodes/space/{spaceId}")
    public ResponseEntity<List<Neo4jKnowledgeNode>> findNodesBySpaceId(@PathVariable Long spaceId) {
        List<Neo4jKnowledgeNode> nodes = neo4jService.findNodesBySpaceId(spaceId);
        return ResponseEntity.ok(nodes);
    }

    /**
     * 查找节点的前置知识（多跳推理）
     */
    @GetMapping("/nodes/{nodeId}/prerequisites")
    public ResponseEntity<List<Neo4jKnowledgeNode>> findPrerequisites(
            @PathVariable String nodeId, 
            @RequestParam Long spaceId) {
        List<Neo4jKnowledgeNode> prerequisites = neo4jService.findPrerequisites(nodeId, spaceId);
        return ResponseEntity.ok(prerequisites);
    }

    /**
     * 查找节点的相关知识
     */
    @GetMapping("/nodes/{nodeId}/related")
    public ResponseEntity<List<Neo4jKnowledgeNode>> findRelatedNodes(
            @PathVariable String nodeId, 
            @RequestParam Long spaceId) {
        List<Neo4jKnowledgeNode> relatedNodes = neo4jService.findRelatedNodes(nodeId, spaceId);
        return ResponseEntity.ok(relatedNodes);
    }

    /**
     * 查找子图（用于图RAG检索）
     */
    @GetMapping("/nodes/{nodeId}/subgraph")
    public ResponseEntity<List<Neo4jKnowledgeNode>> findSubgraph(
            @PathVariable String nodeId, 
            @RequestParam Long spaceId) {
        List<Neo4jKnowledgeNode> subgraph = neo4jService.findSubgraph(nodeId, spaceId);
        return ResponseEntity.ok(subgraph);
    }

    /**
     * 查找两个节点之间的最短路径
     */
    @GetMapping("/nodes/path")
    public ResponseEntity<List<Neo4jKnowledgeNode>> findShortestPath(
            @RequestParam String startNodeId, 
            @RequestParam String endNodeId, 
            @RequestParam Long spaceId) {
        List<Neo4jKnowledgeNode> path = neo4jService.findShortestPath(startNodeId, endNodeId, spaceId);
        return ResponseEntity.ok(path);
    }

    /**
     * 根据名称搜索节点
     */
    @GetMapping("/nodes/search")
    public ResponseEntity<List<Neo4jKnowledgeNode>> searchNodesByName(@RequestParam String name) {
        List<Neo4jKnowledgeNode> nodes = neo4jService.searchNodesByName(name);
        return ResponseEntity.ok(nodes);
    }

    /**
     * 同步MySQL知识图谱到Neo4j
     */
    @PostMapping("/sync/{spaceId}")
    public ResponseEntity<String> syncFromMySQL(@PathVariable Long spaceId) {
        try {
            neo4jService.syncFromMySQL(spaceId);
            return ResponseEntity.ok("同步成功");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("同步失败: " + e.getMessage());
        }
    }
}