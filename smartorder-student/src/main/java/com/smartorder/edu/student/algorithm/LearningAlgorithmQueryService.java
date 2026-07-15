package com.smartorder.edu.student.algorithm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeNodeMastery;
import com.smartorder.edu.knowledge.mapper.KnowledgeGraphNodeMapper;
import com.smartorder.edu.knowledge.mapper.KnowledgeNodeMasteryMapper;
import com.smartorder.edu.student.domain.entity.LearningEvidenceEvent;
import com.smartorder.edu.student.mapper.LearningEvidenceEventMapper;
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
    private final LearningEvidenceEventMapper evidenceEventMapper;

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
        Map<Long, List<LearningEvidenceEvent>> recentEvidenceByNode = loadRecentEvidence(userId, spaceId, nodeIds);

        List<Map<String, Object>> knowledgeStates = nodes.stream()
                .map(node -> toNodeState(node, masteryByNode.get(node.getId()),
                        recentEvidenceByNode.getOrDefault(node.getId(), List.of())))
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
        result.put("nextAction", buildNextAction(weakPoints, knowledgeStates));
        result.put("knowledgeStates", knowledgeStates);
        result.put("algorithmVersion", "bkt_beta_semantic_v1");
        return result;
    }

    public Map<String, Object> getCompactDiagnosis(Long userId, Long spaceId) {
        Map<String, Object> state = getLearningState(userId, spaceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spaceId", spaceId);
        result.put("algorithmVersion", state.get("algorithmVersion"));
        result.put("knowledgeCount", state.get("knowledgeCount"));
        result.put("assessedCount", state.get("assessedCount"));
        result.put("weakPoints", state.get("weakPoints"));
        result.put("nextAction", state.get("nextAction"));
        result.put("diagnosisPolicy", "mastery/confidence 由 BKT+Beta 计算；LLM 只用于题目语义映射和错因归一，不直接修改掌握度。");
        return result;
    }

    private Map<String, Object> toNodeState(KnowledgeGraphNode node,
                                            KnowledgeNodeMastery mastery,
                                            List<LearningEvidenceEvent> recentEvidence) {
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
        item.put("recentEvidence", recentEvidence.stream()
                .limit(3)
                .map(this::toEvidenceSummary)
                .toList());
        return item;
    }

    private Map<Long, List<LearningEvidenceEvent>> loadRecentEvidence(Long userId, Long spaceId, List<Long> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Map.of();
        }
        List<LearningEvidenceEvent> events = evidenceEventMapper.selectList(
                new LambdaQueryWrapper<LearningEvidenceEvent>()
                        .eq(LearningEvidenceEvent::getUserId, userId)
                        .eq(LearningEvidenceEvent::getSpaceId, spaceId)
                        .in(LearningEvidenceEvent::getKnowledgeNodeId, nodeIds)
                        .eq(LearningEvidenceEvent::getIsDeleted, 0)
                        .orderByDesc(LearningEvidenceEvent::getCreateTime)
                        .last("LIMIT 200"));
        return events.stream().collect(Collectors.groupingBy(
                LearningEvidenceEvent::getKnowledgeNodeId,
                LinkedHashMap::new,
                Collectors.toList()));
    }

    private Map<String, Object> toEvidenceSummary(LearningEvidenceEvent event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceType", event.getSourceType());
        item.put("sourceId", event.getSourceId());
        item.put("questionId", event.getQuestionId());
        item.put("score", event.getScore());
        item.put("isCorrect", event.getIsCorrect());
        item.put("evidenceWeight", event.getEvidenceWeight());
        item.put("errorType", event.getErrorType());
        item.put("createdAt", event.getCreateTime());
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

    private Map<String, Object> buildNextAction(List<Map<String, Object>> weakPoints,
                                                List<Map<String, Object>> knowledgeStates) {
        Map<String, Object> action = new LinkedHashMap<>();
        if (!weakPoints.isEmpty()) {
            Map<String, Object> target = weakPoints.get(0);
            double mastery = ((Number) target.get("mastery")).doubleValue();
            double confidence = ((Number) target.get("confidence")).doubleValue();
            action.put("knowledgeNodeId", target.get("knowledgeNodeId"));
            action.put("knowledgeName", target.get("name"));
            action.put("mastery", mastery);
            action.put("confidence", confidence);
            if (confidence < 0.35) {
                action.put("type", "diagnostic_quiz");
                action.put("reason", "该知识点掌握度偏低且证据置信度不足，优先安排诊断题以减少系统不确定性。");
            } else {
                action.put("type", "weakness_practice");
                action.put("reason", "该知识点掌握度偏低且已有一定证据，优先安排补弱练习和针对性讲解。");
            }
            return action;
        }

        Map<String, Object> lowConfidence = knowledgeStates.stream()
                .filter(item -> ((Number) item.get("confidence")).doubleValue() < 0.35)
                .findFirst()
                .orElse(null);
        if (lowConfidence != null) {
            action.put("knowledgeNodeId", lowConfidence.get("knowledgeNodeId"));
            action.put("knowledgeName", lowConfidence.get("name"));
            action.put("mastery", lowConfidence.get("mastery"));
            action.put("confidence", lowConfidence.get("confidence"));
            action.put("type", "verification_quiz");
            action.put("reason", "该知识点缺少足够证据，建议先用验证题确认真实掌握状态。");
            return action;
        }

        action.put("type", "advance_or_review");
        action.put("reason", "当前没有明显薄弱点，可进入后续知识点，或按学习路径安排轻量复习。");
        return action;
    }
}
