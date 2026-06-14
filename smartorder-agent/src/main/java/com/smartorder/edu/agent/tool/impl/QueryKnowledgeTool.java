package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.service.IKnowledgeGraphService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询知识工具——查询知识图谱节点和边
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryKnowledgeTool implements ToolHandler {

    private final IKnowledgeGraphService knowledgeGraphService;

    @Override
    public String name() {
        return "queryKnowledge";
    }

    @Override
    public String description() {
        return "查询知识图谱中的知识点，需要已存在的学习空间ID。返回知识节点和它们之间的关系边。"
                + "注意：该工具仅在已有学习空间时可用，新建规划时请依赖 LLM 自身的学科知识体系。";
    }

    @Override
    public Class<?> requestType() {
        return QueryKnowledgeRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.KNOWLEDGE;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        QueryKnowledgeRequest req = (QueryKnowledgeRequest) request;
        Long spaceId = req.getSpaceId();

        log.info("[QueryKnowledge] spaceId={}, keyword={}", spaceId, req.getKeyword());

        // 防御：spaceId 为空时返回明确提示，避免 ReAct 引擎空转
        if (spaceId == null) {
            return Map.of(
                    "spaceId", (Object) null,
                    "nodeCount", 0,
                    "edgeCount", 0,
                    "nodes", List.of(),
                    "edges", List.of(),
                    "message", "未指定学习空间ID。queryKnowledge 需要已存在的学习空间，新建规划时请跳过知识查询，直接使用 manageLearningPlan 基于 LLM 的学科知识体系生成规划。"
            );
        }

        List<KnowledgeGraphNode> nodes;
        if (req.getKeyword() != null && !req.getKeyword().isBlank()) {
            nodes = knowledgeGraphService.searchNodes(spaceId, req.getKeyword());
        } else {
            nodes = knowledgeGraphService.getNodesBySpaceId(spaceId);
        }

        List<KnowledgeGraphEdge> edges = knowledgeGraphService.getEdgesBySpaceId(spaceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spaceId", spaceId);
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());
        result.put("nodes", nodes.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("name", n.getName());
            m.put("description", n.getDescription());
            m.put("category", n.getCategory());
            m.put("importance", n.getImportance());
            return m;
        }).toList());
        result.put("edges", edges.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fromNodeId", e.getFromNodeId());
            m.put("toNodeId", e.getToNodeId());
            m.put("relationType", e.getRelationType());
            m.put("weight", e.getWeight());
            return m;
        }).toList());

        return result;
    }

    @Data
    public static class QueryKnowledgeRequest {
        @dev.langchain4j.model.output.structured.Description("学习空间ID，必填")
        private Long spaceId;
        @dev.langchain4j.model.output.structured.Description("搜索关键字，可选")
        private String keyword;
    }
}
