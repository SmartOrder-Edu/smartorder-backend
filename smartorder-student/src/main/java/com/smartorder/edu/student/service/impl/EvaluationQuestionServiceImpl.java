package com.smartorder.edu.student.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.util.IdGenerator;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.student.aiservice.QuestionAiService;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.enums.PromptFile;
import com.smartorder.edu.student.domain.enums.QuestionScore;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import com.smartorder.edu.student.util.PromptLoader;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationQuestionServiceImpl implements IEvaluationQuestionService {
    private final QuestionAiService questionAiService;

    private final PromptLoader promptLoader;
    private final OpenAiStreamingChatModel streamingChatModel;
    private final EvaluationQuestionMapper evaluationQuestionMapper;
    private final LearningSpaceMapper learningSpaceMapper;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_COUNT = 5;
    private static final int DEFAULT_DIFFICULTY = 5;
    private static final int DEFAULT_OPTION_COUNT = 4;
    private static final String DEFAULT_PROGRAMMING_LANGUAGE = "Java";
    private static final String DEFAULT_SQL_DIALECT = "MySQL";
    // 提示词文件目录和公共规则文件
    private static final String PROMPTS_DIR = "prompts/";
    private static final String COMMON_RULES_FILE = "prompts/all-evaluation-rules.txt";

    @Override
    public void generateQuestions(GenerateQuestionDTO dto, SseEmitter emitter) {
        // 异步启动后台 Agent 任务，放心地让 Tomcat 主线程秒级返回
        CompletableFuture.runAsync(() -> {
            try {
                log.info("开始生成测评题目，输入参数: {}", dto);
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

                    log.info("准备生成题型={}，难度={}，数量={} 的题目", questionType, difficulty, count);
                    if (QuestionScore.fromCode(questionType) == QuestionScore.UNKNOWN) {
                        throw new BusinessException("暂不支持的题型: " + questionType);
                    }

                    String buildUserPrompt = buildUserPrompt(
                            courseName,
                            topic,
                            questionType,
                            count,
                            difficulty,
                            DEFAULT_OPTION_COUNT
                    );
                    log.info("合并后的提示词：{}", buildUserPrompt);

                    String llmJson = callStreamingModelAndWait(buildUserPrompt,emitter);
                    log.info("LLM完整返回内容 JSON: {}", llmJson);

                    JsonNode questionsNode = parseLlmResponse(llmJson);

                    if (questionsNode.size() != count) {
                        throw new BusinessException(
                                "LLM 返回题目数量不正确，期望 " + count + " 道，实际 " + questionsNode.size() + " 道"
                        );
                    }

                    for (int i = 0; i < questionsNode.size(); i++) {
                        JsonNode q = questionsNode.get(i);

                        EvaluationQuestion question = mapJsonToQuestion(
                                q,
                                dto,
                                courseName,
                                questionType,
                                difficulty,
                                sequence++
                        );

                        evaluationQuestionMapper.insert(question);
                        allQuestions.add(question);
                    }
                }

                log.info("题目生成完成，总数量={}", allQuestions.size());
                // 【核心】：所有题型大循环全部平平安安走完了，主动通知前端通道关闭
                try {
                    emitter.complete();
                } catch (Exception e) {
                    // 说明前端可能早就关闭了网页，没关系，后台任务成功了就行
                }

            } catch (Exception e) {
                log.error("后台异步出题并落库失败: ", e);
                // 发生业务致命异常时，通知前端
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    // 忽略连接早已断开的异常
                }
            }
        });
    }

    /**
     * 3. 构建 prompt
     */
    private String buildUserPrompt(String courseName,
                                   String topic,
                                   String questionType,
                                   int count,
                                   int difficulty,
                                   int optionCount) {

        PromptFile promptFile = PromptFile.fromCode(questionType);

        String commonPrompt = promptLoader.load(COMMON_RULES_FILE);
        String typeRulePrompt = promptLoader.load(PROMPTS_DIR + promptFile.getPromptFileName());

        String mergedPrompt = commonPrompt.replace("{{questionTypeRules}}", typeRulePrompt);

        return PromptTemplate.from(mergedPrompt)
                .apply(Map.of(
                        "courseName", courseName,
                        "topic", topic,
                        "difficulty", difficulty,
                        "count", count,
                        "language", "中文",
                        "optionCount", optionCount,
                        "programmingLanguage", DEFAULT_PROGRAMMING_LANGUAGE,
                        "sqlDialect", DEFAULT_SQL_DIALECT
                ))
                .text();
    }

    /**
     * 4. 调用 StreamingChatModel，并等待流式输出完成
     * <p>
     * 注意：
     * StreamingChatModel.chat(...) 返回值是 void。
     * 所以这里用 StringBuilder 接收 partial token。
     * 再用 CountDownLatch 等待 onCompleteResponse 或 onError 执行。
     */
    private String callStreamingModelAndWait(String userPrompt, SseEmitter emitter) {

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("""
                                你是一个专业的练习题生成专家。
                                你必须严格按照用户要求生成 JSON。
                                不要输出 Markdown。
                                不要输出解释性文字。
                                """),
                        UserMessage.from(userPrompt)
                ))
                .build();

        StringBuilder fullContent = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partialResponse) {
                // 这里就是流式返回的一小段文本
                log.debug("LLM partial: {}", partialResponse);
                fullContent.append(partialResponse);
                try{
                    emitter.send(partialResponse);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                log.info("LLM 流式生成完成");
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                log.error("LLM 流式生成失败", error);
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待 LLM 返回时线程被中断");
        }

        if (errorRef.get() != null) {
            throw new BusinessException("LLM 生成失败: " + errorRef.get().getMessage());
        }

        String result = fullContent.toString().strip();

        if (result.isBlank()) {
            throw new BusinessException("LLM 返回内容为空");
        }

        return result;
    }


    /**
     * 7. 把每一个 JsonNode 题目节点转成 EvaluationQuestion 实体
     */
    private EvaluationQuestion mapJsonToQuestion(JsonNode q,
                                                 GenerateQuestionDTO dto,
                                                 String courseName,
                                                 String questionType,
                                                 int difficulty,
                                                 int sequence) {

        LocalDateTime now = LocalDateTime.now();

        EvaluationQuestion question = new EvaluationQuestion();

        question.setId(IdGenerator.nextId());
        question.setQuestionCode("buildQuestionCode(courseName, questionType, sequence)");

        question.setLearningSpaceId(dto.getLearningSpaceId());
        question.setChapterId(dto.getChapterId());
        question.setKnowledgePointId(dto.getKnowledgePointId());

        question.setQuestionType(readText(q, "questionType", questionType));
        question.setDifficulty(readInt(q, "difficulty", difficulty));

        question.setScore(BigDecimal.valueOf(1));

        question.setStem(readRequiredText(q, "stem"));

        question.setOptionsJson(readJsonArrayAsList(q, "optionsJson"));
        question.setAnswerJson(readJsonObjectAsMap(q, "answerJson"));

        question.setExplanation(readText(q, "explanation", ""));

        /**
         * 如果你的 EvaluationQuestion 实体里面有这些字段，
         * 可以打开下面这几行。
         *
         * question.setAbilityDimension(readText(q, "abilityDimension", ""));
         * question.setCommonMistakesJson(readJsonArrayAsList(q, "commonMistakesJson"));
         * question.setTagsJson(readJsonArrayAsList(q, "tagsJson"));
         */

        question.setGenerationSource("agent_generated");
        question.setStatus(1);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);

        return question;
    }

    private String trimTextOrDefault(String topic, String secondTopic) {
        if (topic == null || topic.isBlank()) {
            return secondTopic;
        }
        return topic.strip();
    }

    /**
     * 5. 解析 LLM 返回的 JSON
     * <p>
     * 期望格式：
     * {
     * "questions": []
     * }
     */
    private JsonNode parseLlmResponse(String response) {
        if (response == null) {
            throw new BusinessException("LLM 返回内容为空");
        }

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
                throw new BusinessException("LLM 返回 JSON 缺少 questions 数组");
            }

            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 LLM 响应失败，原始内容: {}", response, e);
            throw new BusinessException("解析 LLM 响应失败，请检查模型是否返回了合法 JSON");
        }
    }

    /**
     * JsonNode 读取字符串，有默认值
     */
    private String readText(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            return defaultValue;
        }

        String text = value.asText();
        return text == null || text.isBlank() ? defaultValue : text;
    }

    /**
     * JsonNode 读取 int，有默认值
     */
    private int readInt(JsonNode node, String fieldName, int defaultValue) {
        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            return defaultValue;
        }

        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }

        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    /**
     * 读取 JSON 数组字段，转成 List<Map<String, Object>>
     * <p>
     * 例如：
     * "optionsJson": [
     * {"key": "A", "content": "选项A"}
     * ]
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonArrayAsList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            return List.of();
        }

        if (!value.isArray()) {
            throw new BusinessException("字段 " + fieldName + " 必须是 JSON 数组");
        }

        return objectMapper.convertValue(value, List.class);
    }

    /**
     * 读取 JSON 对象字段，转成 Map<String, Object>
     * <p>
     * 例如：
     * "answerJson": {
     * "referenceAnswer": "...",
     * "scoringPoints": []
     * }
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObjectAsMap(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            return Map.of();
        }

        if (!value.isObject()) {
            throw new BusinessException("字段 " + fieldName + " 必须是 JSON 对象");
        }

        return objectMapper.convertValue(value, Map.class);
    }

    /**
     * JsonNode 读取必填字符串
     */
    private String readRequiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);

        if (value == null || value.isNull()) {
            throw new BusinessException("LLM 返回题目缺少必填字段: " + fieldName);
        }

        String text = value.asText();

        if (text == null || text.isBlank()) {
            throw new BusinessException("LLM 返回题目字段为空: " + fieldName);
        }

        return text;
    }
}
