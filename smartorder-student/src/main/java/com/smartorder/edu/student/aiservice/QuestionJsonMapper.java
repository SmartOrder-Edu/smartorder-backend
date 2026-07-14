package com.smartorder.edu.student.aiservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.util.IdGenerator;
import com.smartorder.edu.student.domain.entity.EvaluationCodeTestCase;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.enums.QuestionScore;
import com.smartorder.edu.student.domain.enums.QuestionTypeIsObjective;
import com.smartorder.edu.student.util.JsonNodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * AI 题目 JSON → 实体映射器。
 *
 * <p>职责单一：把大模型返回的单题 JSON 节点映射为 {@link EvaluationQuestion} 实体，
 * 并按题型完成 options / answer / analysis / test_cases 的格式转换。</p>
 *
 * <p>仅处理新格式（question/type/options/answer/score/difficulty/knowledge_points/ability_tags/analysis），
 * 旧格式已废弃。</p>
 *
 * @author 马强
 * @since 2026-07-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionJsonMapper {

    private final ObjectMapper objectMapper;

    /** 映射上下文：封装实体填充所需的请求级参数，避免方法参数过多 */
    public record MappingContext(
            Long questionId,
            Long learningSpaceId,
            Long chapterId,
            String courseName,
            String fallbackQuestionType,
            int fallbackDifficulty,
            int sequence
    ) {}

    /**
     * 将单个题目 JSON 节点映射为 EvaluationQuestion 实体。
     *
     * @param q   题目 JSON 节点（questions 数组中的一个元素）
     * @param ctx 映射上下文
     * @return 填充完成的 EvaluationQuestion 实体
     */
    public EvaluationQuestion mapToEntity(JsonNode q, MappingContext ctx) {
        LocalDateTime now = LocalDateTime.now();
        EvaluationQuestion question = new EvaluationQuestion();

        question.setId(ctx.questionId());
        question.setLearningSpaceId(ctx.learningSpaceId());
        question.setChapterId(ctx.chapterId());

        // type: 中文题型名 → 英文代码
        String typeChinese = JsonNodes.readRequiredText(q, "type");
        String resolvedType = QuestionTypeIsObjective.fromChineseName(typeChinese).getCode();
        question.setQuestionType(resolvedType);

        question.setDifficulty(JsonNodes.readInt(q, "difficulty", ctx.fallbackDifficulty()));
        int defaultScore = QuestionScore.fromCode(resolvedType).getDefaultScore().intValue();
        question.setScore(BigDecimal.valueOf(JsonNodes.readInt(q, "score", defaultScore)));
        question.setStem(JsonNodes.readRequiredText(q, "question"));
        question.setOptions(parseOptions(q));
        question.setCorrectAnswer(parseAnswer(q, resolvedType));

        // analysis 对象 → explanation + commonMistakes + analysisJson
        parseAnalysis(q.get("analysis"), question);

        // knowledge_points / ability_tags
        List<String> knowledgePoints = JsonNodes.readStringList(q, "knowledge_points");
        List<String> abilityTags = JsonNodes.readStringList(q, "ability_tags");
        question.setKnowledgePoints(knowledgePoints);
        question.setAbilityTagsJson(abilityTags);

        // tags: 合并 knowledge_points + ability_tags + 题型代码
        List<String> tags = new LinkedList<>(knowledgePoints);
        tags.addAll(abilityTags);
        tags.add(resolvedType);
        question.setTags(tags);

        question.setQuestionCode(buildQuestionCode(ctx.courseName(), resolvedType, ctx.sequence(), ctx.questionId()));
        question.setSortOrder(ctx.sequence());
        question.setGenerationSource("agent_generated");
        question.setStatus(1);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);

        return question;
    }

    /**
     * 解析编程题测试用例列表。
     *
     * <p>仅 code 题型会包含 {@code test_cases} 数组。解析失败或 test_cases 缺失时返回空列表，
     * 不影响题目本身入库。</p>
     *
     * @param q          题目 JSON 节点
     * @param questionId 已入库的题目 ID
     * @return 测试用例实体列表（可能为空）
     */
    public List<EvaluationCodeTestCase> parseTestCases(JsonNode q, Long questionId) {
        JsonNode testCasesNode = q.get("test_cases");
        if (testCasesNode == null || !testCasesNode.isArray() || testCasesNode.isEmpty()) {
            log.debug("题目未包含 test_cases 或为空: questionId={}", questionId);
            return List.of();
        }

        List<EvaluationCodeTestCase> result = new ArrayList<>();
        int sortOrder = 1;
        LocalDateTime now = LocalDateTime.now();

        for (JsonNode tcNode : testCasesNode) {
            try {
                EvaluationCodeTestCase tc = new EvaluationCodeTestCase();
                tc.setId(IdGenerator.nextId());
                tc.setQuestionId(questionId);
                tc.setInput(JsonNodes.readText(tcNode, "input", ""));
                tc.setExpectedOutput(JsonNodes.readRequiredText(tcNode, "expected_output"));
                tc.setIsSample(JsonNodes.readBoolean(tcNode, "is_sample", false) ? 1 : 0);
                tc.setWeight(BigDecimal.valueOf(JsonNodes.readDouble(tcNode, "weight", 1.0)));
                tc.setTimeLimitMs(JsonNodes.readInt(tcNode, "time_limit_ms", 1000));
                tc.setMemoryLimitMb(JsonNodes.readInt(tcNode, "memory_limit_mb", 128));
                tc.setSortOrder(sortOrder++);
                tc.setStatus(1);
                tc.setCreatedAt(now);
                tc.setUpdatedAt(now);
                result.add(tc);
            } catch (Exception e) {
                log.warn("解析测试用例失败，跳过: questionId={}, caseIndex={}", questionId, sortOrder - 1, e);
            }
        }
        return result;
    }

    // ===== 内部解析方法 =====

    /** options（字符串数组）→ [{label:"A", content:"..."}] */
    private List<Map<String, Object>> parseOptions(JsonNode q) {
        JsonNode optionsNode = q.get("options");
        if (optionsNode == null || !optionsNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < optionsNode.size(); i++) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("label", String.valueOf((char) ('A' + i)));
            option.put("content", optionsNode.get(i).asText());
            result.add(option);
        }
        return result;
    }

    /** answer → correctAnswer Map（按题型转换） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseAnswer(JsonNode q, String questionType) {
        JsonNode answerNode = q.get("answer");
        if (answerNode == null || answerNode.isNull()) {
            return Map.of();
        }
        return switch (questionType) {
            case "single_choice" -> Map.of("correctOptions", List.of(answerNode.asText()));
            case "multiple_choice" -> Map.of("correctOptions",
                    answerNode.isArray()
                            ? objectMapper.convertValue(answerNode, List.class)
                            : List.of(answerNode.asText()));
            case "judge" -> Map.of("correctValue", answerNode.asBoolean());
            case "fill_blank" -> buildFillBlankAnswer(answerNode);
            case "short_answer", "code", "scenario", "sql", "multimodal" ->
                    Map.of("referenceAnswer", answerNode.asText());
            default -> buildFallbackAnswer(answerNode);
        };
    }

    /** 填空题 answer → {blanks: [{index, answer}]} */
    private Map<String, Object> buildFillBlankAnswer(JsonNode answerNode) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> blanks = new ArrayList<>();
        if (answerNode.isArray()) {
            for (int i = 0; i < answerNode.size(); i++) {
                Map<String, Object> blank = new LinkedHashMap<>();
                blank.put("index", i + 1);
                blank.put("answer", answerNode.get(i).asText());
                blanks.add(blank);
            }
        }
        m.put("blanks", blanks);
        return m;
    }

    /** 未知题型的兜底处理 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFallbackAnswer(JsonNode answerNode) {
        if (answerNode.isTextual()) {
            return Map.of("answer", answerNode.asText());
        }
        return objectMapper.convertValue(answerNode, Map.class);
    }

    /** analysis → explanation + commonMistakes + analysisJson */
    @SuppressWarnings("unchecked")
    private void parseAnalysis(JsonNode analysisNode, EvaluationQuestion question) {
        if (analysisNode == null || !analysisNode.isObject()) {
            question.setExplanation("");
            question.setCommonMistakes(List.of());
            question.setAnalysisJson(Map.of());
            return;
        }
        question.setAnalysisJson(objectMapper.convertValue(analysisNode, Map.class));
        question.setExplanation(extractCorrectReason(analysisNode));
        question.setCommonMistakes(extractWrongReason(analysisNode));
    }

    /** correct_reason：字符串直接返回，对象拼接各 value */
    private String extractCorrectReason(JsonNode analysisNode) {
        JsonNode correctReason = analysisNode.get("correct_reason");
        if (correctReason == null || correctReason.isNull()) {
            return "";
        }
        if (correctReason.isTextual()) {
            return correctReason.asText();
        }
        if (correctReason.isObject()) {
            StringBuilder sb = new StringBuilder();
            correctReason.fields().forEachRemaining(entry -> {
                if (!sb.isEmpty()) {
                    sb.append("；");
                }
                sb.append(entry.getKey()).append("：").append(entry.getValue().asText());
            });
            return sb.toString();
        }
        return "";
    }

    /** wrong_reason（{A:"原因"}）→ [{option:"A", reason:"..."}] */
    private List<Map<String, Object>> extractWrongReason(JsonNode analysisNode) {
        JsonNode wrongReason = analysisNode.get("wrong_reason");
        if (wrongReason == null || !wrongReason.isObject() || wrongReason.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        wrongReason.fields().forEachRemaining(entry -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("option", entry.getKey());
            item.put("reason", entry.getValue().asText());
            result.add(item);
        });
        return result;
    }

    /** 拼接题目唯一编码：课程名-题型-序号-题目ID */
    private String buildQuestionCode(String courseName, String questionType, int sequence, Long questionId) {
        String normalizedCourse = courseName == null ? "course" : courseName.strip().replaceAll("\\s+", "-");
        return normalizedCourse + "-" + questionType + "-" + sequence + "-" + questionId;
    }
}
