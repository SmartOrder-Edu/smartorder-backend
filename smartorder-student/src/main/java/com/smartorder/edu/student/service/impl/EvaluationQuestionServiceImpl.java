package com.smartorder.edu.student.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.enums.PromptFile;
import com.smartorder.edu.student.domain.enums.QuestionScore;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationQuestionServiceImpl implements IEvaluationQuestionService {

    private final ChatModel chatmodel;
    private final EvaluationQuestionMapper evaluationQuestionMapper;
    private final LearningSpaceMapper learningSpaceMapper;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_COUNT = 5;
    private static final int DEFAULT_DIFFICULTY = 3;

    private static final String PROMPTS_DIR = "prompts/";
    private static final String COMMON_RULES_FILE = "evaluation-question-common-rules.txt";


    @Override
    public List<EvaluationQuestion> generateQuestions(GenerateQuestionDTO dto) {
        String courseName = dto.getCourseName();
        if (courseName == null || courseName.isBlank()) {
            LearningSpace learningSpace = learningSpaceMapper.selectById(dto.getLearningSpaceId());
            courseName = learningSpace.getName();
        }
        String topic = trimTextOrDefault(dto.getTopic(), "基础知识");

        List<EvaluationQuestion> allQuestions = new ArrayList<>();
        int sequence = 1;

        for (GenerateQuestionDTO.QuestionTypeCountDTO typeCount : dto.getQuestionTypeCountList()) {
            String questionType = typeCount.getQuestionType();
            int count = typeCount.getCount() != null ? typeCount.getCount() : DEFAULT_COUNT;
            int difficulty = typeCount.getDifficulty() != null ? typeCount.getDifficulty() : DEFAULT_DIFFICULTY;

            if (QuestionScore.fromCode(questionType)==QuestionScore.UNKNOWN) {
                throw new BusinessException("暂不支持的题型: " + questionType);
            }

            String promptTemplate = loadPromptTemplate(questionType);
            List<EvaluationQuestion> questions = generateByType(
                    dto, courseName, topic, questionType, difficulty, count, promptTemplate, sequence
            );
            allQuestions.addAll(questions);
            sequence += questions.size();
        }
        return allQuestions;
    }

    /**
     * 对单个题型调用 LLM 批量生成题目并入库
     */
    private List<EvaluationQuestion> generateByType(GenerateQuestionDTO dto, String courseName, String topic,
                                                     String questionType, int difficulty, int count,
                                                     String promptTemplate, int startSequence) {
        String prompt = promptTemplate
                .replace("{courseName}", courseName)
                .replace("{topic}", topic)
                .replace("{difficulty}", String.valueOf(difficulty))
                .replace("{count}", String.valueOf(count));

        String llmResponse;
        try {
            llmResponse = chatmodel.chat(
                    SystemMessage.from(prompt),
                    UserMessage.from("请严格按照上述要求输出 JSON，不要输出其他内容。")
            );
        } catch (Exception e) {
            log.error("题型={} 调用 LLM 失败", questionType, e);
            throw new BusinessException("题目生成失败：AI 服务调用异常");
        }

        log.debug("题型={} 难度={} 数量={}，LLM 原始响应:\n{}", questionType, difficulty, count, llmResponse);

        JsonNode questionsNode = parseLlmResponse(llmResponse);
        List<EvaluationQuestion> questions = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < questionsNode.size(); i++) {
            JsonNode q = questionsNode.get(i);
            EvaluationQuestion question = new EvaluationQuestion();
            question.setId(com.smartorder.edu.common.util.IdGenerator.nextId());
            question.setQuestionCode(buildQuestionCode(courseName, questionType, startSequence + i));
            question.setLearningSpaceId(dto.getLearningSpaceId());
            question.setChapterId(dto.getChapterId());
            question.setKnowledgePointId(dto.getKnowledgePointId());
            question.setQuestionType(questionType);
            question.setDifficulty(difficulty);
            question.setScore(QuestionScore.fromCode(questionType).getDefaultScore());
            question.setAbilityDimension(getTextOrDefault(q, "abilityDimension", "concept"));
            question.setStem(q.get("stem").asText());
            question.setOptionsJson(readJsonField(q, "optionsJson"));
            question.setAnswerJson(readJsonObject(q, "answerJson"));
            question.setExplanation(getTextOrDefault(q, "explanation", ""));
            question.setCommonMistakesJson(readJsonField(q, "commonMistakesJson"));
            question.setTagsJson(readStringList(q, "tagsJson"));
            question.setGenerationSource("agent_generated");
            question.setStatus(1);
            question.setCreatedAt(now);
            question.setUpdatedAt(now);
            evaluationQuestionMapper.insert(question);
            questions.add(question);
        }

        if (questions.size() != count) {
            log.warn("题型={} 请求生成 {} 道，实际返回 {} 道", questionType, count, questions.size());
        }
        return questions;
    }

    /**
     * 加载题型提示词：通用规则 + 题型专用规则拼接
     */
    private String loadPromptTemplate(String questionType) {
        String commonRules = loadClasspathResource(PROMPTS_DIR + COMMON_RULES_FILE);
        String typePrompt = loadClasspathResource(PROMPTS_DIR + PromptFile.fromCode(questionType).getPromptFileName());
        return commonRules.strip() + "\n\n" + typePrompt.strip();
    }

    /**
     * 解析 LLM 返回的 JSON，兼容 markdown 代码块包裹的情况
     */
    private JsonNode parseLlmResponse(String response) {
        String json = response.strip();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).strip();
            }
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode questions = root.get("questions");
            if (questions == null || !questions.isArray()) {
                throw new BusinessException("LLM 返回的 JSON 缺少 questions 数组");
            }
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 LLM 响应失败，原始响应:\n{}", response, e);
            throw new BusinessException("题目生成失败：LLM 返回数据格式异常");
        }
    }

    private String buildQuestionCode(String courseName, String questionType, int sequence) {
        String prefix = courseName.replaceAll("[^a-zA-Z0-9]", "").substring(0, Math.min(4, courseName.length())).toUpperCase();
        String typePrefix = questionType.substring(0, Math.min(3, questionType.length())).toUpperCase();
        return String.format("%s-%s-%04d", prefix, typePrefix, sequence);
    }

    // ---- JSON 字段读取工具方法 ----

    private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? defaultValue : value.asText();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return List.of();
        return objectMapper.convertValue(value, List.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObject(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return Map.of();
        return objectMapper.convertValue(value, Map.class);
    }

    private List<String> readStringList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(n -> result.add(n.asText()));
        return result;
    }

    // ---- 通用工具方法 ----

    private static String loadClasspathResource(String path) {
        try (InputStream is = EvaluationQuestionServiceImpl.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new BusinessException("找不到提示词文件: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException("读取提示词文件失败: " + path + ", 原因: " + e.getMessage());
        }
    }

    private static String trimTextOrDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
