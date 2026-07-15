package com.smartorder.edu.student.algorithm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeNodeMastery;
import com.smartorder.edu.knowledge.mapper.KnowledgeNodeMasteryMapper;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionKnowledgeNode;
import com.smartorder.edu.student.domain.entity.LearningEvidenceEvent;
import com.smartorder.edu.student.mapper.EvaluationQuestionKnowledgeNodeMapper;
import com.smartorder.edu.student.mapper.LearningEvidenceEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationLearningAlgorithmService {

    private static final String SOURCE_TYPE = "evaluation_answer_record";

    private final EvaluationQuestionKnowledgeNodeMapper questionKnowledgeNodeMapper;
    private final LearningEvidenceEventMapper evidenceEventMapper;
    private final KnowledgeNodeMasteryMapper masteryMapper;
    private final EvidenceWeightCalculator evidenceWeightCalculator;
    private final LearningSemanticAnalyzer semanticAnalyzer;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processGradedRecord(EvaluationAnswerRecord record, EvaluationQuestion question) {
        if (record == null || question == null || record.getIsCorrect() == null || record.getId() == null) {
            return;
        }
        if ("system_error".equals(record.getErrorType())) {
            log.info("[LearningAlgorithm] system error record is not learning evidence, skip recordId={}", record.getId());
            return;
        }
        List<EvaluationQuestionKnowledgeNode> mappings = loadOrCreateMappings(question);
        if (mappings.isEmpty()) {
            log.info("[LearningAlgorithm] no knowledge mapping, skip recordId={}, questionId={}",
                    record.getId(), question.getId());
            return;
        }

        for (EvaluationQuestionKnowledgeNode mapping : mappings) {
            if (alreadyProcessed(record.getId(), mapping.getKnowledgeNodeId())) {
                continue;
            }
            LearningEvidenceEvent evidence = buildEvidence(record, question, mapping);
            evidenceEventMapper.insert(evidence);
            updateMastery(evidence, mapping);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processGradedRecords(List<EvaluationAnswerRecord> records, Map<Long, EvaluationQuestion> questionMap) {
        if (records == null || questionMap == null) {
            return;
        }
        for (EvaluationAnswerRecord record : records) {
            if (record.getIsCorrect() == null) {
                continue;
            }
            try {
                processGradedRecord(record, questionMap.get(record.getQuestionId()));
            } catch (Exception e) {
                log.warn("[LearningAlgorithm] process failed: recordId={}, questionId={}, error={}",
                        record.getId(), record.getQuestionId(), e.getMessage(), e);
            }
        }
    }

    private List<EvaluationQuestionKnowledgeNode> loadOrCreateMappings(EvaluationQuestion question) {
        List<EvaluationQuestionKnowledgeNode> mappings = questionKnowledgeNodeMapper.selectList(
                new LambdaQueryWrapper<EvaluationQuestionKnowledgeNode>()
                        .eq(EvaluationQuestionKnowledgeNode::getQuestionId, question.getId())
                        .eq(EvaluationQuestionKnowledgeNode::getIsDeleted, 0));
        if (!mappings.isEmpty()) {
            return mappings;
        }

        List<SemanticKnowledgeMapping> semanticMappings = semanticAnalyzer.resolveMappings(question);
        List<EvaluationQuestionKnowledgeNode> created = new ArrayList<>();
        for (SemanticKnowledgeMapping semantic : semanticMappings) {
            EvaluationQuestionKnowledgeNode mapping = new EvaluationQuestionKnowledgeNode();
            mapping.setQuestionId(question.getId());
            mapping.setKnowledgeNodeId(semantic.knowledgeNodeId());
            mapping.setWeight(semantic.weight());
            mapping.setSource(semantic.source());
            mapping.setConfidence(semantic.confidence());
            mapping.setReason(semantic.reason());
            mapping.setIsDeleted(0);
            try {
                questionKnowledgeNodeMapper.insert(mapping);
                created.add(mapping);
            } catch (Exception e) {
                log.debug("[LearningAlgorithm] mapping insert skipped: questionId={}, nodeId={}, error={}",
                        question.getId(), semantic.knowledgeNodeId(), e.getMessage());
            }
        }
        return created;
    }

    private boolean alreadyProcessed(Long recordId, Long knowledgeNodeId) {
        Long count = evidenceEventMapper.selectCount(new LambdaQueryWrapper<LearningEvidenceEvent>()
                .eq(LearningEvidenceEvent::getSourceType, SOURCE_TYPE)
                .eq(LearningEvidenceEvent::getSourceId, recordId)
                .eq(LearningEvidenceEvent::getKnowledgeNodeId, knowledgeNodeId)
                .eq(LearningEvidenceEvent::getIsDeleted, 0));
        return count != null && count > 0;
    }

    private LearningEvidenceEvent buildEvidence(EvaluationAnswerRecord record,
                                                EvaluationQuestion question,
                                                EvaluationQuestionKnowledgeNode mapping) {
        BigDecimal normalizedScore = normalizedScore(record, question);
        BigDecimal baseWeight = evidenceWeightCalculator.calculate(record, question);
        BigDecimal mappingWeight = mapping.getWeight() != null ? mapping.getWeight() : BigDecimal.ONE;
        BigDecimal evidenceWeight = baseWeight.multiply(mappingWeight).setScale(4, RoundingMode.HALF_UP);
        String normalizedErrorType = semanticAnalyzer.normalizeErrorType(record, question);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questionType", question.getQuestionType());
        payload.put("questionScore", question.getScore());
        payload.put("rawScore", record.getScore());
        payload.put("mappingSource", mapping.getSource());
        payload.put("mappingReason", mapping.getReason());
        payload.put("rawErrorType", record.getErrorType());
        payload.put("errorReason", record.getErrorReason());

        LearningEvidenceEvent evidence = new LearningEvidenceEvent();
        evidence.setUserId(record.getUserId());
        evidence.setSpaceId(record.getLearningSpaceId());
        evidence.setSourceType(SOURCE_TYPE);
        evidence.setSourceId(record.getId());
        evidence.setQuestionId(question.getId());
        evidence.setKnowledgeNodeId(mapping.getKnowledgeNodeId());
        evidence.setScore(normalizedScore);
        evidence.setIsCorrect(record.getIsCorrect());
        evidence.setDifficulty(question.getDifficulty());
        evidence.setTimeSpentSeconds(record.getDuration());
        evidence.setExpectedSeconds(evidenceWeightCalculator.expectedSeconds(question));
        evidence.setHintCount(record.getUsedHint() != null ? record.getUsedHint() : 0);
        evidence.setEvidenceWeight(evidenceWeight);
        evidence.setErrorType(normalizedErrorType);
        evidence.setSemanticConfidence(mapping.getConfidence());
        evidence.setPayload(payload);
        evidence.setIsDeleted(0);
        evidence.setCreateTime(LocalDateTime.now());
        return evidence;
    }

    private BigDecimal normalizedScore(EvaluationAnswerRecord record, EvaluationQuestion question) {
        BigDecimal score = record.getScore() != null ? record.getScore() : BigDecimal.ZERO;
        BigDecimal total = question.getScore() != null && question.getScore().compareTo(BigDecimal.ZERO) > 0
                ? question.getScore()
                : BigDecimal.ONE;
        BigDecimal normalized = score.divide(total, 4, RoundingMode.HALF_UP);
        if (normalized.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (normalized.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return normalized;
    }

    private void updateMastery(LearningEvidenceEvent evidence, EvaluationQuestionKnowledgeNode mapping) {
        KnowledgeNodeMastery mastery = masteryMapper.selectOne(new LambdaQueryWrapper<KnowledgeNodeMastery>()
                .eq(KnowledgeNodeMastery::getUserId, evidence.getUserId())
                .eq(KnowledgeNodeMastery::getNodeId, evidence.getKnowledgeNodeId())
                .eq(KnowledgeNodeMastery::getIsDeleted, 0));

        boolean insert = false;
        if (mastery == null) {
            mastery = new KnowledgeNodeMastery();
            mastery.setUserId(evidence.getUserId());
            mastery.setNodeId(evidence.getKnowledgeNodeId());
            mastery.setMasteryLevel(0.25);
            mastery.setConfidence(0.0);
            mastery.setAssessmentCount(0);
            mastery.setIsDeleted(0);
            mastery.setCreateTime(LocalDateTime.now());
            insert = true;
        }

        Map<String, Object> sources = parseEvidenceSources(mastery.getEvidenceSources());
        Map<String, Object> beta = getMap(sources, "beta");
        double alpha = asDouble(beta.get("alpha"), 1.0);
        double betaValue = asDouble(beta.get("beta"), 1.0);
        int evidenceCount = ((Number) beta.getOrDefault("evidenceCount", mastery.getAssessmentCount() != null
                ? mastery.getAssessmentCount() : 0)).intValue();

        double score = evidence.getScore().doubleValue();
        double weight = evidence.getEvidenceWeight().doubleValue();
        double previousMastery = mastery.getMasteryLevel() != null ? mastery.getMasteryLevel() : 0.25;
        double bktNext = updateBkt(previousMastery, score >= 0.60);
        double effectiveWeight = Math.min(1.0, Math.max(0.05, weight));
        double weightedBkt = previousMastery + (bktNext - previousMastery) * effectiveWeight;

        alpha += weight * score;
        betaValue += weight * (1.0 - score);
        evidenceCount += 1;
        double betaMean = alpha / (alpha + betaValue);
        double displayMastery = clamp(0.70 * weightedBkt + 0.30 * betaMean, 0.0, 0.99);
        double confidence = Math.min(0.95, evidenceCount / (evidenceCount + 5.0));

        Map<String, Object> bkt = new LinkedHashMap<>();
        bkt.put("pLearned", round4(weightedBkt));
        bkt.put("pGuess", 0.20);
        bkt.put("pSlip", 0.10);
        bkt.put("pTransition", 0.08);
        bkt.put("lastObservation", score >= 0.60 ? "correct" : "incorrect");

        Map<String, Object> updatedBeta = new LinkedHashMap<>();
        updatedBeta.put("alpha", round4(alpha));
        updatedBeta.put("beta", round4(betaValue));
        updatedBeta.put("mean", round4(betaMean));
        updatedBeta.put("evidenceCount", evidenceCount);

        Map<String, Object> lastEvidence = new LinkedHashMap<>();
        lastEvidence.put("sourceType", evidence.getSourceType());
        lastEvidence.put("sourceId", evidence.getSourceId());
        lastEvidence.put("questionId", evidence.getQuestionId());
        lastEvidence.put("score", evidence.getScore());
        lastEvidence.put("weight", evidence.getEvidenceWeight());
        lastEvidence.put("errorType", evidence.getErrorType());
        lastEvidence.put("mappingSource", mapping.getSource());
        lastEvidence.put("semanticConfidence", mapping.getConfidence());

        sources.put("bkt", bkt);
        sources.put("beta", updatedBeta);
        sources.put("lastEvidence", lastEvidence);

        mastery.setMasteryLevel(round4(displayMastery));
        mastery.setConfidence(round4(confidence));
        mastery.setLastAssessedAt(LocalDateTime.now());
        mastery.setAssessmentCount((mastery.getAssessmentCount() != null ? mastery.getAssessmentCount() : 0) + 1);
        mastery.setEvidenceSources(toJson(sources));
        mastery.setUpdateTime(LocalDateTime.now());

        if (insert) {
            masteryMapper.insert(mastery);
        } else {
            masteryMapper.updateById(mastery);
        }
    }

    private double updateBkt(double learned, boolean correct) {
        double guess = 0.20;
        double slip = 0.10;
        double transition = 0.08;
        double posterior;
        if (correct) {
            posterior = learned * (1.0 - slip)
                    / (learned * (1.0 - slip) + (1.0 - learned) * guess);
        } else {
            posterior = learned * slip
                    / (learned * slip + (1.0 - learned) * (1.0 - guess));
        }
        return posterior + (1.0 - posterior) * transition;
    }

    private Map<String, Object> parseEvidenceSources(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.debug("[LearningAlgorithm] invalid evidence_sources json, reset: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        root.put(key, created);
        return created;
    }

    private double asDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
