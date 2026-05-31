package com.smartorder.edu.student.service.impl;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.util.IdGenerator;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationItem;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationJob;
import com.smartorder.edu.student.domain.enums.PromptFile;
import com.smartorder.edu.student.domain.enums.QuestionScore;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationItemMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationJobMapper;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.smartorder.edu.common.util.ThreadLocalUtils.getCurrentUserId;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationQuestionServiceImpl implements IEvaluationQuestionService {
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "evaluation-question-generator-" + THREAD_COUNTER.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    private final PromptLoader promptLoader;
    private final OpenAiStreamingChatModel streamingChatModel;
    private final EvaluationQuestionMapper evaluationQuestionMapper;
    private final LearningSpaceMapper learningSpaceMapper;
    private final ObjectMapper objectMapper;
    private final EvaluationQuestionGenerationJobMapper generationJobMapper;
    private final EvaluationQuestionGenerationItemMapper generationItemMapper;
    private final TransactionTemplate transactionTemplate;

    private static final int DEFAULT_COUNT = 5;
    private static final int DEFAULT_DIFFICULTY = 5;
    private static final int DEFAULT_OPTION_COUNT = 4;
    private static final int DEFAULT_MAX_RETRY_COUNT = 3;
    private static final String DEFAULT_PROGRAMMING_LANGUAGE = "Java";
    private static final String DEFAULT_SQL_DIALECT = "MySQL";
    private static final String PROMPTS_DIR = "prompts/";
    private static final String COMMON_RULES_FILE = "prompts/all-evaluation-rules.txt";

    @Override
    public void generateQuestions(GenerateQuestionDTO dto, SseEmitter emitter) {
        GenerationContext context = transactionTemplate.execute(
                status -> {
                    try {
                        return createGenerationContext(dto);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        );
        if (context == null) {
            throw new BusinessException("Failed to create question generation job");
        }
        sendEvent(emitter, "job-created", Map.of(
                "jobId", context.job().getId(),
                "jobNo", context.job().getJobNo(),
                "totalCount", context.job().getTotalCount(),
                "description", "问题开始生成"
        ));

        CompletableFuture.runAsync(() -> runGeneration(context, emitter), executor);
    }

    private GenerationContext createGenerationContext(GenerateQuestionDTO dto) throws Exception {
        if (dto.getQuestionTypeCountList() == null || dto.getQuestionTypeCountList().isEmpty()) {
            throw new BusinessException("questionTypeCountList cannot be empty");
        }

        String courseName = resolveCourseName(dto);
        String topic = trimTextOrDefault(dto.getTopic(), "basic knowledge");
        LocalDateTime now = LocalDateTime.now();
        int totalCount = dto.getQuestionTypeCountList().stream()
                .mapToInt(typeCount -> resolveCount(typeCount.getCount()))
                .sum();

        long id = IdGenerator.nextId();
        EvaluationQuestionGenerationJob job = EvaluationQuestionGenerationJob.builder()
                .id(id)
                .jobNo("QG-" + id)
                .userId(getCurrentUserId())
                .learningSpaceId(dto.getLearningSpaceId())
                .chapterId(dto.getChapterId())
                .knowledgePointId(dto.getKnowledgePointId())
                .courseName(courseName)
                .topic(topic)
                .requestPayload(toJson(dto))
                .status("pending")
                .totalCount(totalCount)
                .successCount(0)
                .failedCount(0)
                .progress(0)
                .currentStep("created")
                .createdAt(now)
                .updatedAt(now)
                .build();
        generationJobMapper.insert(job);

        List<EvaluationQuestionGenerationItem> items = createGenerationItems(job.getId(), dto, now);
        generationItemMapper.insertBatch(items);


        return new GenerationContext(dto, courseName, topic, job, items);
    }

    private List<EvaluationQuestionGenerationItem> createGenerationItems(Long jobId, GenerateQuestionDTO dto, LocalDateTime now) {
        List<EvaluationQuestionGenerationItem> items = new ArrayList<>();
        int sequenceNo = 1;

        for (GenerateQuestionDTO.QuestionTypeCountDTO typeCount : dto.getQuestionTypeCountList()) {
            int count = resolveCount(typeCount.getCount());
            int difficulty = resolveDifficulty(typeCount.getDifficulty());
            int optionCount = resolveOptionCount(typeCount.getOptionCount());

            for (int i = 0; i < count; i++) {
                EvaluationQuestionGenerationItem item = EvaluationQuestionGenerationItem.builder()
                        .id(IdGenerator.nextId())
                        .jobId(jobId)
                        .questionType(typeCount.getQuestionType())
                        .difficulty(difficulty)
                        .optionCount(optionCount)
                        .sequenceNo(sequenceNo++)
                        .status("pending")
                        .retryCount(0)
                        .maxRetryCount(DEFAULT_MAX_RETRY_COUNT)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                items.add(item);
            }
        }
        return items;
    }

    private void runGeneration(GenerationContext context, SseEmitter emitter) {
        int successCount = 0;
        Set<Long> successfulItemIds = ConcurrentHashMap.newKeySet();
        int itemCursor = 0;

        updateJobRunning(context.job().getId());

        try {
            for (GenerateQuestionDTO.QuestionTypeCountDTO typeCount : context.dto().getQuestionTypeCountList()) {
                String questionType = typeCount.getQuestionType();
                int count = resolveCount(typeCount.getCount());
                int difficulty = resolveDifficulty(typeCount.getDifficulty());
                int optionCount = resolveOptionCount(typeCount.getOptionCount());
                List<EvaluationQuestionGenerationItem> batchItems =
                        context.items().subList(itemCursor, itemCursor + count);
                itemCursor += count;

                markItemsRunning(batchItems);

                log.info("Preparing question generation: jobId={}, questionType={}, difficulty={}, count={}",
                        context.job().getId(), questionType, difficulty, count);
                if (QuestionScore.fromCode(questionType) == QuestionScore.UNKNOWN) {
                    throw new BusinessException("Unsupported question type: " + questionType);
                }

                String userPrompt = buildUserPrompt(
                        context.courseName(),
                        context.topic(),
                        questionType,
                        count,
                        difficulty,
                        optionCount
                );
                log.debug("Built question prompt: jobId={}, questionType={}, length={}",
                        context.job().getId(), questionType, userPrompt.length());

                updateJobStep(context.job().getId(), "generating " + questionType);
                String llmJson = callStreamingModelAndWait(userPrompt, emitter, questionType, context.job().getId());
                JsonNode questionsNode = parseLlmResponse(llmJson);

                if (questionsNode.size() != count) {
                    throw new BusinessException("LLM returned " + questionsNode.size()
                            + " questions, expected " + count);
                }

                for (int i = 0; i < questionsNode.size(); i++) {
                    EvaluationQuestionGenerationItem item = batchItems.get(i);
                    Long questionId = IdGenerator.nextId();
                    EvaluationQuestion question = mapJsonToQuestion(
                            questionId,
                            questionsNode.get(i),
                            context.dto(),
                            context.courseName(),
                            questionType,
                            difficulty,
                            item.getSequenceNo()
                    );

                    evaluationQuestionMapper.insert(question);
                    markItemSuccess(item.getId(), questionId, llmJson);
                    successfulItemIds.add(item.getId());
                    successCount++;
                    updateJobProgress(context.job().getId(), successCount, 0, context.job().getTotalCount(),
                            "generated " + successCount + "/" + context.job().getTotalCount());
                }
            }

            updateJobFinished(context.job().getId(), "success", successCount, 0, null);
            completeEmitter(emitter);
        } catch (Exception e) {
            int failedCount = context.job().getTotalCount() - successfulItemIds.size();
            markUnfinishedItemsFailed(context.items(), successfulItemIds, e);
            updateJobFinished(context.job().getId(), resolveTerminalStatus(successfulItemIds.size(), failedCount),
                    successfulItemIds.size(), failedCount, e.getMessage());
            log.error("Async question generation failed: jobId={}", context.job().getId(), e);
            completeEmitterWithError(emitter, e);
        }
    }

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
                        "language", "zh-CN",
                        "optionCount", optionCount,
                        "programmingLanguage", DEFAULT_PROGRAMMING_LANGUAGE,
                        "sqlDialect", DEFAULT_SQL_DIALECT
                ))
                .text();
    }

    private String callStreamingModelAndWait(String userPrompt, SseEmitter emitter, String questionType, Long jobId) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        SystemMessage.from("""
                                You are a professional practice question generation expert.
                                Return valid JSON only. Do not output Markdown, explanation, or reasoning.
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
                log.debug("LLM partial: {}", partialResponse);
                fullContent.append(partialResponse);
                sendEvent(emitter, "quiz-token", Map.of(
                        "jobId", jobId,
                        "questionType", questionType,
                        "chunk", partialResponse
                ));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                log.info("LLM streaming generation completed");
                latch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                log.error("LLM streaming generation failed", error);
                errorRef.set(error);
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("Interrupted while waiting for LLM response");
        }

        if (errorRef.get() != null) {
            throw new BusinessException("LLM generation failed: " + errorRef.get().getMessage());
        }

        String result = fullContent.toString().strip();
        if (result.isBlank()) {
            throw new BusinessException("LLM response is empty");
        }

        return result;
    }

    private EvaluationQuestion mapJsonToQuestion(Long questionId,
                                                 JsonNode q,
                                                 GenerateQuestionDTO dto,
                                                 String courseName,
                                                 String questionType,
                                                 int difficulty,
                                                 int sequence) {

        LocalDateTime now = LocalDateTime.now();
        EvaluationQuestion question = new EvaluationQuestion();

        question.setId(questionId);
        question.setQuestionCode(buildQuestionCode(courseName, questionType, sequence, questionId));
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
        question.setAbilityDimension(readText(q, "abilityDimension", null));
        question.setCommonMistakesJson(readJsonArrayAsList(q, "commonMistakesJson"));
        question.setTagsJson(readJsonArrayAsStringList(q, "tagsJson"));
        question.setGenerationSource("agent_generated");
        question.setStatus(1);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);

        return question;
    }

    private void updateJobRunning(Long jobId) {
        updateJobStep(jobId, "running");
    }

    private void updateJobStep(Long jobId, String currentStep) {
        EvaluationQuestionGenerationJob update = new EvaluationQuestionGenerationJob();
        update.setId(jobId);
        update.setCurrentStep(currentStep);
        update.setUpdatedAt(LocalDateTime.now());
        generationJobMapper.updateById(update);
    }

    private void updateJobProgress(Long jobId, int successCount, int failedCount, int totalCount, String currentStep) {
        EvaluationQuestionGenerationJob update = new EvaluationQuestionGenerationJob();
        update.setId(jobId);
        update.setSuccessCount(successCount);
        update.setFailedCount(failedCount);
        update.setProgress(calculateProgress(successCount, failedCount, totalCount));
        update.setCurrentStep(currentStep);
        update.setUpdatedAt(LocalDateTime.now());
        generationJobMapper.updateById(update);
    }

    private void updateJobFinished(Long jobId, String status, int successCount, int failedCount, String errorMessage) {
        EvaluationQuestionGenerationJob update = new EvaluationQuestionGenerationJob();
        update.setId(jobId);
        update.setStatus(status);
        update.setSuccessCount(successCount);
        update.setFailedCount(failedCount);
        update.setProgress(calculateProgress(successCount, failedCount, successCount + failedCount));
        update.setCurrentStep("finished");
        update.setErrorMessage(errorMessage);
        update.setFinishedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        generationJobMapper.updateById(update);
    }

    private void markItemsRunning(List<EvaluationQuestionGenerationItem> items) {
        for (EvaluationQuestionGenerationItem item : items) {
            EvaluationQuestionGenerationItem update = new EvaluationQuestionGenerationItem();
            update.setId(item.getId());
            update.setStatus("running");
            update.setStartedAt(LocalDateTime.now());
            update.setUpdatedAt(LocalDateTime.now());
            generationItemMapper.updateById(update);
        }
    }

    private void markItemSuccess(Long itemId, Long questionId, String rawResponse) {
        EvaluationQuestionGenerationItem update = new EvaluationQuestionGenerationItem();
        update.setId(itemId);
        update.setQuestionId(questionId);
        update.setStatus("success");
        update.setRawResponse(rawResponse);
        update.setFinishedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());
        generationItemMapper.updateById(update);
    }

    private void markUnfinishedItemsFailed(List<EvaluationQuestionGenerationItem> items, Set<Long> successfulItemIds, Exception e) {
        for (EvaluationQuestionGenerationItem item : items) {
            if (successfulItemIds.contains(item.getId())) {
                continue;
            }
            EvaluationQuestionGenerationItem update = new EvaluationQuestionGenerationItem();
            update.setId(item.getId());
            update.setStatus("failed");
            update.setErrorMessage(e.getMessage());
            update.setFinishedAt(LocalDateTime.now());
            update.setUpdatedAt(LocalDateTime.now());
            generationItemMapper.updateById(update);
        }
    }

    private String resolveCourseName(GenerateQuestionDTO dto) {
        if (dto.getCourseName() != null && !dto.getCourseName().isBlank()) {
            return dto.getCourseName().strip();
        }

        LearningSpace learningSpace = learningSpaceMapper.selectById(dto.getLearningSpaceId());
        if (learningSpace == null || learningSpace.getName() == null || learningSpace.getName().isBlank()) {
            return "unknown course";
        }
        return learningSpace.getName();
    }

    private String toJson(GenerateQuestionDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize generation request");
        }
    }

    private int resolveCount(Integer count) {
        return count == null ? DEFAULT_COUNT : count;
    }

    private int resolveDifficulty(Integer difficulty) {
        return difficulty == null ? DEFAULT_DIFFICULTY : difficulty;
    }

    private int resolveOptionCount(Integer optionCount) {
        return optionCount == null ? DEFAULT_OPTION_COUNT : optionCount;
    }

    private int calculateProgress(int successCount, int failedCount, int totalCount) {
        if (totalCount <= 0) {
            return 100;
        }
        return Math.min(100, (successCount + failedCount) * 100 / totalCount);
    }

    private String resolveTerminalStatus(int successCount, int failedCount) {
        if (failedCount == 0) {
            return "success";
        }
        return successCount > 0 ? "partial_success" : "failed";
    }

    private String trimTextOrDefault(String topic, String defaultTopic) {
        if (topic == null || topic.isBlank()) {
            return defaultTopic;
        }
        return topic.strip();
    }

    private String buildQuestionCode(String courseName, String questionType, int sequence, Long questionId) {
        String normalizedCourse = courseName == null ? "course" : courseName.strip().replaceAll("\\s+", "-");
        return normalizedCourse + "-" + questionType + "-" + sequence + "-" + questionId;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(JSON.toJSONString(data)));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send skipped: event={}, reason={}", name, e.getMessage());
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE complete skipped: {}", e.getMessage());
        }
    }

    private void completeEmitterWithError(SseEmitter emitter, Exception error) {
        try {
            emitter.completeWithError(error);
        } catch (Exception e) {
            log.debug("SSE error completion skipped: {}", e.getMessage());
        }
    }

    private JsonNode parseLlmResponse(String response) {
        if (response == null) {
            throw new BusinessException("LLM response is empty");
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
                throw new BusinessException("LLM response JSON lacks questions array");
            }
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", response, e);
            throw new BusinessException("Failed to parse LLM response as valid JSON");
        }
    }

    private String readText(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }

        String text = value.asText();
        return text == null || text.isBlank() ? defaultValue : text;
    }

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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonArrayAsList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }

        if (!value.isArray()) {
            throw new BusinessException("Field " + fieldName + " must be a JSON array");
        }

        return objectMapper.convertValue(value, List.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> readJsonArrayAsStringList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }

        if (!value.isArray()) {
            throw new BusinessException("Field " + fieldName + " must be a JSON array");
        }

        return objectMapper.convertValue(value, List.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObjectAsMap(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return Map.of();
        }

        if (!value.isObject()) {
            throw new BusinessException("Field " + fieldName + " must be a JSON object");
        }

        return objectMapper.convertValue(value, Map.class);
    }

    private String readRequiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            throw new BusinessException("LLM question lacks required field: " + fieldName);
        }

        String text = value.asText();
        if (text == null || text.isBlank()) {
            throw new BusinessException("LLM question field is empty: " + fieldName);
        }

        return text;
    }

    private record GenerationContext(
            GenerateQuestionDTO dto,
            String courseName,
            String topic,
            EvaluationQuestionGenerationJob job,
            List<EvaluationQuestionGenerationItem> items
    ) {
    }
}
