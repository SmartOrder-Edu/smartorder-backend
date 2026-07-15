package com.smartorder.edu.student.algorithm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.mapper.KnowledgeGraphNodeMapper;
import com.smartorder.edu.student.domain.entity.EvaluationAnswerRecord;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
public class LearningSemanticAnalyzer {

    private final KnowledgeGraphNodeMapper knowledgeGraphNodeMapper;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final boolean llmEnabled;

    public LearningSemanticAnalyzer(
            KnowledgeGraphNodeMapper knowledgeGraphNodeMapper,
            @Qualifier("lessonScriptChatModel") ChatModel chatModel,
            ObjectMapper objectMapper,
            @Value("${algorithm.semantic.llm-enabled:true}") boolean llmEnabled) {
        this.knowledgeGraphNodeMapper = knowledgeGraphNodeMapper;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.llmEnabled = llmEnabled;
    }

    public List<SemanticKnowledgeMapping> resolveMappings(EvaluationQuestion question) {
        if (question == null || question.getLearningSpaceId() == null) {
            return List.of();
        }
        List<KnowledgeGraphNode> candidates = loadCandidates(question.getLearningSpaceId());
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<SemanticKnowledgeMapping> tagMatches = matchByTags(question, candidates);
        if (!tagMatches.isEmpty()) {
            return tagMatches;
        }

        if (!llmEnabled) {
            return List.of();
        }

        try {
            List<SemanticKnowledgeMapping> llmMappings = askLlm(question, candidates);
            if (!llmMappings.isEmpty()) {
                return llmMappings;
            }
        } catch (Exception e) {
            log.warn("[LearningSemanticAnalyzer] LLM mapping failed, fallback to no mapping: questionId={}, error={}",
                    question.getId(), e.getMessage());
        }
        return List.of();
    }

    public String normalizeErrorType(EvaluationAnswerRecord record, EvaluationQuestion question) {
        String raw = record != null ? record.getErrorType() : null;
        if (raw == null || raw.isBlank()) {
            return record != null && record.getIsCorrect() != null && record.getIsCorrect() == 1 ? null : "unknown";
        }
        String normalized = normalizeKnownError(raw);
        if (!"unknown".equals(normalized) || !llmEnabled) {
            return normalized;
        }
        try {
            return askLlmForErrorType(raw, record, question);
        } catch (Exception e) {
            log.warn("[LearningSemanticAnalyzer] LLM error normalization failed: raw={}, error={}", raw, e.getMessage());
            return "unknown";
        }
    }

    private List<KnowledgeGraphNode> loadCandidates(Long spaceId) {
        return knowledgeGraphNodeMapper.selectList(new LambdaQueryWrapper<KnowledgeGraphNode>()
                .eq(KnowledgeGraphNode::getSpaceId, spaceId)
                .eq(KnowledgeGraphNode::getIsDeleted, 0)
                .last("LIMIT 50"));
    }

    private List<SemanticKnowledgeMapping> matchByTags(EvaluationQuestion question, List<KnowledgeGraphNode> candidates) {
        Set<String> labels = new LinkedHashSet<>();
        if (question.getKnowledgePoints() != null) {
            labels.addAll(question.getKnowledgePoints());
        }
        if (question.getTags() != null) {
            labels.addAll(question.getTags());
        }
        if (labels.isEmpty()) {
            return List.of();
        }

        List<SemanticKnowledgeMapping> matches = new ArrayList<>();
        for (KnowledgeGraphNode node : candidates) {
            String nodeName = normalize(node.getName());
            for (String label : labels) {
                String tag = normalize(label);
                if (!tag.isBlank() && (nodeName.equals(tag) || nodeName.contains(tag) || tag.contains(nodeName))) {
                    matches.add(new SemanticKnowledgeMapping(
                            node.getId(),
                            BigDecimal.ONE,
                            BigDecimal.valueOf(0.90),
                            "tag_match",
                            "Question tag matched knowledge node name"));
                    break;
                }
            }
        }
        return normalizeWeights(matches);
    }

    private List<SemanticKnowledgeMapping> askLlm(EvaluationQuestion question, List<KnowledgeGraphNode> candidates) throws Exception {
        String response = chatModel.chat(buildMappingPrompt(question, candidates));
        JsonNode root = objectMapper.readTree(stripCodeFence(response));
        JsonNode mappings = root.path("mappings");
        if (!mappings.isArray()) {
            return List.of();
        }

        Set<Long> candidateIds = new LinkedHashSet<>();
        for (KnowledgeGraphNode candidate : candidates) {
            candidateIds.add(candidate.getId());
        }

        List<SemanticKnowledgeMapping> result = new ArrayList<>();
        for (JsonNode item : mappings) {
            Long nodeId = item.path("knowledgeNodeId").isNumber() ? item.path("knowledgeNodeId").asLong() : null;
            if (nodeId == null || !candidateIds.contains(nodeId)) {
                continue;
            }
            double confidence = clamp(item.path("confidence").asDouble(0.0), 0.0, 1.0);
            if (confidence < 0.55) {
                continue;
            }
            double weight = clamp(item.path("weight").asDouble(1.0), 0.05, 1.0);
            String reason = item.path("reason").asText("LLM semantic mapping");
            result.add(new SemanticKnowledgeMapping(
                    nodeId,
                    BigDecimal.valueOf(weight).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP),
                    "llm_suggested",
                    reason));
        }
        return normalizeWeights(result);
    }

    private String askLlmForErrorType(String raw, EvaluationAnswerRecord record, EvaluationQuestion question) throws Exception {
        String prompt = """
                You normalize a learning error label into one enum.
                Allowed values: concept_confusion, condition_misuse, procedure_gap, calculation_error,
                memory_decay, transfer_failure, careless_mistake, expression_gap, unknown.
                Return JSON only: {"errorType":"..."}.

                Raw error: %s
                Question type: %s
                Question stem: %s
                Error reason: %s
                """.formatted(
                raw,
                question != null ? question.getQuestionType() : "",
                question != null ? truncate(question.getStem(), 600) : "",
                record != null ? truncate(record.getErrorReason(), 600) : "");
        String response = chatModel.chat(prompt);
        JsonNode root = objectMapper.readTree(stripCodeFence(response));
        return normalizeKnownError(root.path("errorType").asText("unknown"));
    }

    private String buildMappingPrompt(EvaluationQuestion question, List<KnowledgeGraphNode> candidates) {
        StringBuilder candidateText = new StringBuilder();
        for (KnowledgeGraphNode node : candidates) {
            candidateText.append("- id=").append(node.getId())
                    .append(", name=").append(node.getName())
                    .append(", category=").append(nullToEmpty(node.getCategory()))
                    .append(", description=").append(truncate(node.getDescription(), 160))
                    .append('\n');
        }

        return """
                You map an evaluation question to existing knowledge nodes.
                Select at most 3 nodes from candidates. Do not invent IDs.
                Return JSON only:
                {"mappings":[{"knowledgeNodeId":123,"weight":1.0,"confidence":0.8,"reason":"short reason"}]}

                Question:
                type: %s
                difficulty: %s
                stem: %s
                tags: %s
                knowledgePoints: %s

                Candidates:
                %s
                """.formatted(
                question.getQuestionType(),
                question.getDifficulty(),
                truncate(question.getStem(), 1000),
                question.getTags(),
                question.getKnowledgePoints(),
                candidateText);
    }

    private List<SemanticKnowledgeMapping> normalizeWeights(List<SemanticKnowledgeMapping> mappings) {
        if (mappings.isEmpty()) {
            return mappings;
        }
        BigDecimal total = mappings.stream()
                .map(SemanticKnowledgeMapping::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return mappings;
        }
        List<SemanticKnowledgeMapping> normalized = new ArrayList<>();
        for (SemanticKnowledgeMapping mapping : mappings) {
            normalized.add(new SemanticKnowledgeMapping(
                    mapping.knowledgeNodeId(),
                    mapping.weight().divide(total, 4, RoundingMode.HALF_UP),
                    mapping.confidence(),
                    mapping.source(),
                    mapping.reason()));
        }
        return normalized;
    }

    private String normalizeKnownError(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "concept_confusion", "concept_missing", "confused_concept" -> "concept_confusion";
            case "condition_misuse" -> "condition_misuse";
            case "procedure_gap", "incomplete_answer", "logic_error" -> "procedure_gap";
            case "calculation_error" -> "calculation_error";
            case "memory_decay" -> "memory_decay";
            case "transfer_failure" -> "transfer_failure";
            case "careless_mistake", "wrong_option", "wrong_options", "wrong_judgment" -> "careless_mistake";
            case "expression_gap" -> "expression_gap";
            default -> "unknown";
        };
    }

    private String stripCodeFence(String text) {
        if (text == null) {
            return "{}";
        }
        return text.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("^```\\s*", "")
                .replaceAll("\\s*```$", "");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
