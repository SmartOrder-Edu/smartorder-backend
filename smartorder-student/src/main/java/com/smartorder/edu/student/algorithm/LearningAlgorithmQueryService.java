package com.smartorder.edu.student.algorithm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeNodeMastery;
import com.smartorder.edu.knowledge.mapper.KnowledgeGraphNodeMapper;
import com.smartorder.edu.knowledge.mapper.KnowledgeNodeMasteryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LearningAlgorithmQueryService {

    private final KnowledgeGraphNodeMapper knowledgeGraphNodeMapper;
    private final KnowledgeNodeMasteryMapper masteryMapper;

    public Map<String, Object> getLearningState(Long userId, Long spaceId) {
        List<KnowledgeGraphNode> nodes = knowledgeGraphNodeMapper.selectList(
                new LambdaQueryWrapper<KnowledgeGraphNode>()
                        .eq(KnowledgeGraphNode::getSpaceId, spaceId)
                        .eq(KnowledgeGraphNode::getIsDeleted, 0));
        List<Long> nodeIds = nodes.stream().map(KnowledgeGraphNode::getId).toList();

        List<KnowledgeNodeMastery> masteryList = nodeIds.isEmpty()
                ? List.of()
                : masteryMapper.selectList(new LambdaQueryWrapper<KnowledgeNodeMastery>()
                .eq(KnowledgeNodeMastery::getUserId, userId)
                .in(KnowledgeNodeMastery::getNodeId, nodeIds)
                .eq(KnowledgeNodeMastery::getIsDeleted, 0));

        Map<Long, KnowledgeNodeMastery> masteryByNode = masteryList.stream()
                .collect(Collectors.toMap(KnowledgeNodeMastery::getNodeId, Function.identity(), (a, b) -> a));

        List<Map<String, Object>> knowledgeStates = nodes.stream()
                .map(node -> toNodeState(node, masteryByNode.get(node.getId())))
                .sorted(Comparator
                        .comparing((Map<String, Object> item) -> ((Number) item.get("mastery")).doubleValue())
                        .thenComparing(item -> -((Number) item.get("importance")).intValue()))
                .toList();

        List<Map<String, Object>> weakPoints = knowledgeStates.stream()
                .filter(item -> ((Number) item.get("mastery")).doubleValue() < 0.60)
                .limit(8)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spaceId", spaceId);
        result.put("knowledgeCount", nodes.size());
        result.put("assessedCount", masteryList.size());
        result.put("weakPoints", weakPoints);
        result.put("knowledgeStates", knowledgeStates);
        result.put("algorithmVersion", "bkt_beta_semantic_v1");
        return result;
    }

    private Map<String, Object> toNodeState(KnowledgeGraphNode node, KnowledgeNodeMastery mastery) {
        double masteryLevel = mastery != null && mastery.getMasteryLevel() != null ? mastery.getMasteryLevel() : 0.25;
        double confidence = mastery != null && mastery.getConfidence() != null ? mastery.getConfidence() : 0.0;
        int assessmentCount = mastery != null && mastery.getAssessmentCount() != null ? mastery.getAssessmentCount() : 0;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("knowledgeNodeId", node.getId());
        item.put("name", node.getName());
        item.put("category", node.getCategory());
        item.put("importance", node.getImportance() != null ? node.getImportance() : 3);
        item.put("mastery", masteryLevel);
        item.put("confidence", confidence);
        item.put("assessmentCount", assessmentCount);
        item.put("level", levelOf(masteryLevel, confidence));
        item.put("lastAssessedAt", mastery != null ? mastery.getLastAssessedAt() : null);
        item.put("explanationFacts", mastery != null ? mastery.getEvidenceSources() : null);
        return item;
    }

    private String levelOf(double mastery, double confidence) {
        if (confidence < 0.25) {
            return "diagnose_needed";
        }
        if (mastery < 0.35) {
            return "weak";
        }
        if (mastery < 0.60) {
            return "partial";
        }
        if (mastery < 0.80) {
            return "basic";
        }
        if (mastery < 0.92) {
            return "proficient";
        }
        return "mastered";
    }
}
