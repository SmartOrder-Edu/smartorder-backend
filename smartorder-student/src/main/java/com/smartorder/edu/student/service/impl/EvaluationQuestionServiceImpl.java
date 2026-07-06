package com.smartorder.edu.student.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.util.IdGenerator;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.student.aiservice.QuestionAiService;
import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationItem;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationJob;
import com.smartorder.edu.student.domain.enums.PromptFile;
import com.smartorder.edu.student.domain.enums.QuestionJobPlan;
import com.smartorder.edu.student.domain.enums.QuestionScore;
import com.smartorder.edu.student.domain.vo.GenerateQuestionVO;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationItemMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionGenerationJobMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.service.IEvaluationQuestionService;
import com.smartorder.edu.student.util.StudentPromptFileLoader;
import dev.langchain4j.model.input.PromptTemplate;
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
import java.util.function.Consumer;

import static com.smartorder.edu.common.util.ThreadLocalUtils.getCurrentUserId;

/**
 * 测评题目生成服务实现类（题库生成核心逻辑）。
 *
 * <p>整体流程：
 * <ol>
 *   <li>接收 {@link GenerateQuestionDTO} 请求，在事务中创建「生成任务(job)」与「生成明细(item)」。</li>
 *   <li>按题型将明细拆分为多个批次，批量并行调用大模型流式生成题目。</li>
 *   <li>解析大模型返回的 JSON，落库到 {@code evaluation_question} 题库表。</li>
 *   <li>全程通过 SSE 向前端推送进度事件，并实时更新 job/item 状态。</li>
 * </ol>
 * </p>
 *
 * <p>任务执行在自定义守护线程池中异步进行，避免阻塞 HTTP 请求线程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationQuestionServiceImpl implements IEvaluationQuestionService {
    /** 用于给生成线程命名的自增计数器 */
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    /** 题目生成专用线程池（缓存线程池 + 守护线程），每个生成任务/批次都在其中执行 */
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "evaluation-question-generator-" + THREAD_COUNTER.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    private final StudentPromptFileLoader studentPromptFileLoader;
    private final EvaluationQuestionMapper evaluationQuestionMapper;
    private final LearningSpaceMapper learningSpaceMapper;
    private final ObjectMapper objectMapper;
    private final EvaluationQuestionGenerationJobMapper generationJobMapper;
    private final EvaluationQuestionGenerationItemMapper generationItemMapper;
    private final TransactionTemplate transactionTemplate;
    private final QuestionAiService questionAiService;

    /** 每题型默认生成数量 */
    private static final int DEFAULT_COUNT = 3;
    /** 默认难度等级（1-10） */
    private static final int DEFAULT_DIFFICULTY = 5;
    /** 选择题默认选项数 */
    private static final int DEFAULT_OPTION_COUNT = 4;
    /** 单条明细最大重试次数 */
    private static final int DEFAULT_MAX_RETRY_COUNT = 3;
    /** 编程题默认编程语言 */
    private static final String DEFAULT_PROGRAMMING_LANGUAGE = "Java";
    /** SQL 题默认数据库方言 */
    private static final String DEFAULT_SQL_DIALECT = "MySQL";
    /** 提示词文件所在目录 */
    private static final String PROMPTS_DIR = "prompts/";
    /** 通用出题规则提示词文件（所有题型共享） */
    private static final String COMMON_RULES_FILE = "prompts/all-evaluation-rules.txt";

    @Override
    public void generateQuestions(GenerateQuestionDTO dto, SseEmitter emitter) {
        // createGenerationContext 内部只抛 BusinessException(RuntimeException)，
        // TransactionTemplate 遇到 RuntimeException 会自动回滚并向上抛出，
        // 因此无需 try-catch 包装，也无需 null 检查（execute 不会返回 null）。
        // 在事务中创建生成任务(job)与生成明细(item)，保证初始数据一致。
        GenerationContext context = transactionTemplate.execute(status -> createGenerationContext(dto));
        log.info("生成问题任务开始: jobId={}, jobNo={}, totalCount={}",
                context.job().getId(), context.job().getJobNo(), context.job().getTotalCount());
        // SSE 推送「任务开始」事件，便于前端初始化进度条
        sendEvent(emitter, QuestionJobPlan.QUESTION_JOB_PLAN.getEventName(), Map.of(
                "jobId", context.job().getId(),
                "jobNo", context.job().getJobNo(),
                "totalCount", context.job().getTotalCount(),
                "description", "生成问题任务开始"
        ));

        // 提交异步生成任务，立即返回，后续流程通过 SSE 推送进度
        CompletableFuture.runAsync(() -> runGeneration(context, emitter), executor);
    }

    @Override
    public List<GenerateQuestionVO.QuestionVO> listQuestions(Long learningSpaceId, Integer limit) {
        // 归一化分页参数：默认30条，范围限制在 1~100 之间，防止过量查询
        int normalizedLimit = limit == null ? 30 : Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<EvaluationQuestion> query = new LambdaQueryWrapper<EvaluationQuestion>()
                .eq(EvaluationQuestion::getStatus, 1)
                .orderByDesc(EvaluationQuestion::getCreatedAt)
                .last("LIMIT " + normalizedLimit);

        // 学习空间ID不为空时，按学习空间过滤
        if (learningSpaceId != null) {
            query.eq(EvaluationQuestion::getLearningSpaceId, learningSpaceId);
        }

        // 仅查询启用状态(status=1)的题目，并转换为对外视图对象
        return evaluationQuestionMapper.selectList(query)
                .stream()
                .map(GenerateQuestionVO.QuestionVO::from)
                .toList();
    }

    /**
     * 在事务中构建生成上下文：校验请求、解析课程名，并落库「生成任务(job)」与「生成明细(item)」。
     *
     * @param dto 出题请求参数
     * @return 包含 job、item 列表及课程/主题信息的生成上下文
     */
    private GenerationContext createGenerationContext(GenerateQuestionDTO dto) {
        if (dto.getQuestionTypeCountList() == null || dto.getQuestionTypeCountList().isEmpty()) {
            throw new BusinessException("问题类型数量列表不能为空");
        }

        String courseName = resolveCourseName(dto);
        String topic = trimTextOrDefault(dto.getTopic(), "basic knowledge");
        LocalDateTime now = LocalDateTime.now();
        // 累加各题型请求数量，得到任务总题数
        int totalCount = dto.getQuestionTypeCountList().stream()
                .mapToInt(typeCount -> resolveCount(typeCount.getCount()))
                .sum();

        long id = IdGenerator.nextId();
        Long userId = resolveCurrentUserId();
        // 构建生成任务(job)记录，初始状态为 pending
        EvaluationQuestionGenerationJob job = EvaluationQuestionGenerationJob.builder()
                .id(id)
                .jobNo("QG-" + id)
                .userId(userId)
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

        // 构建并批量落库生成明细(item)，每道题对应一条明细
        List<EvaluationQuestionGenerationItem> items = createGenerationItems(job.getId(), dto, now);
        generationItemMapper.insertBatch(items);


        return new GenerationContext(dto, courseName, topic, job, items);
    }

    /**
     * 根据请求中各题型的「数量」展开成多条生成明细(item)，每条明细代表一个待生成的题目。
     */
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

    /**
     * 异步执行整批题目生成的主流程。
     *
     * <p>将明细按题型拆分为多个批次并行生成；成功时更新任务为 success，失败时标记未完成的
     * 明细为 failed 并将任务置为 partial_success/failed，并通过 SSE 推送错误。</p>
     */
    private void runGeneration(GenerationContext context, SseEmitter emitter) {
        // 成功计数（线程安全）；已成功生成题目的 itemId 集合，用于失败兜底时区分成功/失败
        AtomicInteger successCount = new AtomicInteger(0);
        Set<Long> successfulItemIds = ConcurrentHashMap.newKeySet();

        updateJobRunning(context.job().getId());

        try {
            // 把待生成的 item 分成多个批次，每个批次包含同一题型的若干题目，批次之间可以并行生成
            List<GenerationBatch> batches = createGenerationBatches(context);
            validateGenerationBatches(batches);
            log.info("并行生成问题开始: jobId={}, batchCount={}, totalCount={}",
                    context.job().getId(), batches.size(), context.job().getTotalCount());

            // 每个批次提交到线程池并行生成
            List<CompletableFuture<Void>> futures = batches.stream()
                    .map(batch -> CompletableFuture.runAsync(
                            () -> generateBatch(context, batch, emitter, successCount, successfulItemIds),
                            executor
                    ))
                    .toList();
            waitForBatches(futures);

            updateJobFinished(context.job().getId(), "success", successCount.get(), 0, null);
            log.info("生成问题任务完成: jobId={}, successCount={}, failedCount=0",
                    context.job().getId(), successCount.get());

            completeEmitter(emitter);

        } catch (Exception e) {
            // 失败兜底：未成功的明细标记为 failed，并按成功/失败比例确定任务终态
            int failedCount = context.job().getTotalCount() - successfulItemIds.size();
            markUnfinishedItemsFailed(context.items(), successfulItemIds, e);
            updateJobFinished(context.job().getId(), resolveTerminalStatus(successfulItemIds.size(), failedCount),
                    successfulItemIds.size(), failedCount, e.getMessage());
            log.error("异步生成问题失败: jobId={}, successCount={}, failedCount={}",
                    context.job().getId(), successfulItemIds.size(), failedCount, e);
            completeEmitterWithError(emitter, e);
        }
    }

    /**
     * 将生成明细(item)按「题型 + 数量」切分为多个批次(GenerationBatch)，同一题型的题目归到同一批次。
     *
     * @throws BusinessException 若明细数量与请求数量不一致
     */
    private List<GenerationBatch> createGenerationBatches(GenerationContext context) {
        List<GenerationBatch> batches = new ArrayList<>();
        int itemCursor = 0;

        for (GenerateQuestionDTO.QuestionTypeCountDTO typeCount : context.dto().getQuestionTypeCountList()) {
            int count = resolveCount(typeCount.getCount());
            if (itemCursor + count > context.items().size()) {
                throw new BusinessException("生成的题目数量与请求数量items().size()不匹配");
            }

            // 从全局 item 列表中截取属于该题型的 count 条明细
            List<EvaluationQuestionGenerationItem> batchItems =
                    List.copyOf(context.items().subList(itemCursor, itemCursor + count));
            itemCursor += count;

            batches.add(new GenerationBatch(
                    typeCount.getQuestionType(),
                    count,
                    resolveDifficulty(typeCount.getDifficulty()),
                    resolveOptionCount(typeCount.getOptionCount()),
                    batchItems
            ));
        }

        if (itemCursor != context.items().size()) {
            throw new BusinessException("生成的题目数量与请求数量items().size()不匹配");
        }
        return batches;
    }

    /**
     * 校验批次中的题型是否受支持（映射到 {@link QuestionScore} 枚举）。
     */
    private void validateGenerationBatches(List<GenerationBatch> batches) {
        for (GenerationBatch batch : batches) {
            if (QuestionScore.fromCode(batch.questionType()) == QuestionScore.UNKNOWN) {
                throw new BusinessException("不支持的题目类型questionType: " + batch.questionType());
            }
        }
    }

    /**
     * 阻塞等待所有批次生成完成；若任一批次抛异常则向上传播（RuntimeException 原样抛出）。
     */
    private void waitForBatches(List<CompletableFuture<Void>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BusinessException("题目生成失败: " + cause.getMessage());
        }
    }

    /**
     * 生成单个题型批次：构建提示词 → 调用大模型流式生成 → 解析 JSON → 逐题落库 → 更新进度。
     */
    private void generateBatch(GenerationContext context,
                               GenerationBatch batch,
                               SseEmitter emitter,
                               AtomicInteger successCount,
                               Set<Long> successfulItemIds) {

        // 先将本批次所有 item 标记为 running
        markItemsRunning(batch.items());

        log.info("准备生成题型批次: jobId={}, questionType={}, difficulty={}, count={}",
                context.job().getId(), batch.questionType(), batch.difficulty(), batch.count());

        // 根据课程/主题/题型/难度等构建大模型提示词
        String userPrompt = buildUserPrompt(
                context.courseName(),
                context.topic(),
                batch.questionType(),
                batch.count(),
                batch.difficulty(),
                batch.optionCount()
        );
        log.debug("生成问题提示词构建完成: jobId={}, questionType={}, length={}",
                context.job().getId(), batch.questionType(), userPrompt.length());

        updateJobStep(context.job().getId(), "generating " + batch.questionType());
        // 流式调用大模型并等待完整响应，过程中通过 SSE 实时推送 token
        String llmJson = callStreamingModelAndWait(userPrompt, emitter, batch.questionType(), context.job().getId());
        JsonNode questionsNode = parseLlmResponse(llmJson);

        // 校验返回题目数量是否与请求一致
        if (questionsNode.size() != batch.count()) {
            throw new BusinessException("LLM返回了 " + questionsNode.size() + " 个题目, 期望 " + batch.count() + " 个");
        }

        // 逐题映射为实体并落库，同时更新 item 状态与任务进度
        for (int i = 0; i < questionsNode.size(); i++) {
            EvaluationQuestionGenerationItem item = batch.items().get(i);
            Long questionId = IdGenerator.nextId();
            EvaluationQuestion question = mapJsonToQuestion(
                    questionId,
                    questionsNode.get(i),
                    context.dto(),
                    context.courseName(),
                    batch.questionType(),
                    batch.difficulty(),
                    item.getSequenceNo()
            );

            evaluationQuestionMapper.insert(question);
            markItemSuccess(item.getId(), questionId, llmJson);
            successfulItemIds.add(item.getId());
            int generatedCount = successCount.incrementAndGet();
            updateJobProgress(context.job().getId(), generatedCount, 0, context.job().getTotalCount(),
                    "generated " + generatedCount + "/" + context.job().getTotalCount());
        }
        log.info("题型批次生成完成: jobId={}, questionType={}, count={}",
                context.job().getId(), batch.questionType(), batch.count());
    }

    /**
     * 构建大模型用户提示词：加载通用规则 + 题型专属规则，并用请求参数渲染模板占位符。
     */
    private String buildUserPrompt(String courseName,
                                   String topic,
                                   String questionType,
                                   int count,
                                   int difficulty,
                                   int optionCount) {

        // 根据题型加载对应提示词文件（如 sql/code 等专属规则）
        PromptFile promptFile = PromptFile.fromCode(questionType);
        String commonPrompt = studentPromptFileLoader.load(COMMON_RULES_FILE);
        String typeRulePrompt = studentPromptFileLoader.load(PROMPTS_DIR + promptFile.getPromptFileName());
        // 将题型专属规则注入通用规则的 {{questionTypeRules}} 占位符
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

    /**
     * 流式调用大模型并等待完整响应。
     *
     * <p>通过 Reactor Flux 订阅流式输出，每收到一个分片就追加到 fullContent 并通过 SSE 推送；
     * 使用 CountDownLatch 阻塞至流结束或出错。返回拼接后的完整文本。</p>
     */
    private String callStreamingModelAndWait(String userPrompt, SseEmitter emitter, String questionType, Long jobId) {
        StringBuilder fullContent = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try {
            questionAiService.generateQuestions(userPrompt)
                    .subscribe(
                            partialResponse -> {
                                log.debug("LLM streaming chunk: {}", partialResponse);
                                fullContent.append(partialResponse);
                                // 实时把流式片段推送给前端
                                sendEvent(emitter, questionType,
                                        Map.of(
                                                "questionType", questionType,
                                                "jobId", jobId,
                                                "chunk", partialResponse
                                        ));
                            },
                            error -> {
                                log.error("LLM streaming generation failed", error);
                                errorRef.set(error);
                                latch.countDown();
                            },
                            () -> {
                                log.info("LLM streaming generation completed");
                                latch.countDown();
                            }
                    );
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待LLM响应时被中断");
        } catch (Exception e) {
            throw new BusinessException("LLM生成失败: " + e.getMessage());
        }

        if (errorRef.get() != null) {
            throw new BusinessException("LLM生成失败: " + errorRef.get().getMessage());
        }

        String result = fullContent.toString().strip();
        if (result.isBlank()) {
            throw new BusinessException("LLM响应为空");
        }

        return result;
    }

    /**
     * 将大模型返回的单个题目 JSON 节点映射为 {@link EvaluationQuestion} 题库实体。
     */
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
        // 标记为 Agent 生成来源，状态置为启用
        question.setGenerationSource("agent_generated");
        question.setStatus(1);
        question.setCreatedAt(now);
        question.setUpdatedAt(now);

        return question;
    }

    /** 任务进入运行状态：更新 currentStep=running */
    private void updateJobRunning(Long jobId) {
        buildAndUpdate(jobId, update -> update.setCurrentStep("running"));
    }

    /** 更新任务当前步骤描述（如 generating single_choice） */
    private void updateJobStep(Long jobId, String currentStep) {
        buildAndUpdate(jobId, update -> update.setCurrentStep(currentStep));
    }

    /** 更新任务进度：成功数、失败数与百分比进度 */
    private void updateJobProgress(Long jobId, int successCount, int failedCount, int totalCount, String currentStep) {
        buildAndUpdate(jobId, update -> {
            update.setSuccessCount(successCount);
            update.setFailedCount(failedCount);
            update.setProgress(calculateProgress(successCount, failedCount, totalCount));
            update.setCurrentStep(currentStep);
        });
    }

    /** 任务结束：写入终态(status)、成功/失败数、进度、错误信息与结束时间 */
    private void updateJobFinished(Long jobId, String status, int successCount, int failedCount, String errorMessage) {
        buildAndUpdate(jobId, update -> {
            update.setStatus(status);
            update.setSuccessCount(successCount);
            update.setFailedCount(failedCount);
            update.setProgress(calculateProgress(successCount, failedCount, successCount + failedCount));
            update.setCurrentStep("finished");
            update.setErrorMessage(errorMessage);
            update.setFinishedAt(LocalDateTime.now());
        });
    }

    /** 通用更新工具：构建仅含 jobId 与 updatedAt 的更新对象，套用自定义字段后写库 */
    private void buildAndUpdate(Long jobId, Consumer<EvaluationQuestionGenerationJob> customizer) {
        EvaluationQuestionGenerationJob update = new EvaluationQuestionGenerationJob();
        update.setId(jobId);
        update.setUpdatedAt(LocalDateTime.now());
        customizer.accept(update);
        generationJobMapper.updateById(update);
    }

    /** 将一批明细(item)标记为 running，并记录开始时间 */
    private void markItemsRunning(List<EvaluationQuestionGenerationItem> items) {
        UpdateWrapper<EvaluationQuestionGenerationItem> wrapper = new UpdateWrapper<EvaluationQuestionGenerationItem>()
                .in("id", items.stream().map(EvaluationQuestionGenerationItem::getId).toList())
                .set("status", "running")
                .set("started_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now());
        generationItemMapper.update(null, wrapper);
    }

    /** 单条明细生成成功：关联 questionId、记录原始响应、状态置 success */
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

    /** 失败兜底：将未成功的明细标记为 failed，并记录异常信息 */
    private void markUnfinishedItemsFailed(List<EvaluationQuestionGenerationItem> items, Set<Long> successfulItemIds, Exception e) {
        UpdateWrapper<EvaluationQuestionGenerationItem> wrapper = new UpdateWrapper<EvaluationQuestionGenerationItem>()
                .in("id", items.stream()
                        .filter(item -> !successfulItemIds.contains(item.getId()))
                        .map(EvaluationQuestionGenerationItem::getId)
                        .toList())
                .set("status", "failed")
                .set("error_message", e.getMessage())
                .set("finished_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now());
        generationItemMapper.update(null, wrapper);
    }

    /** 解析当前登录用户ID（用于 job 归属），失败则抛业务异常 */
    private Long resolveCurrentUserId() {
        try {
            return getCurrentUserId();
        } catch (Exception e) {
            throw new BusinessException("获取当前用户ID失败: " + e.getMessage());
        }
    }

    /** 解析课程名称：优先用请求入参，否则从学习空间回查 */
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

    /** 将请求 DTO 序列化为 JSON，用于存储到 job 的 requestPayload（便于回溯原始请求） */
    private String toJson(GenerateQuestionDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            throw new BusinessException("序列化生成请求失败");
        }
    }

    /** 数量参数兜底：为空时使用默认数量 */
    private int resolveCount(Integer count) {
        return count == null ? DEFAULT_COUNT : count;
    }

    /** 难度参数兜底：为空时使用默认难度 */
    private int resolveDifficulty(Integer difficulty) {
        return difficulty == null ? DEFAULT_DIFFICULTY : difficulty;
    }

    /** 选项数参数兜底：为空时使用默认选项数（仅选择题生效） */
    private int resolveOptionCount(Integer optionCount) {
        return optionCount == null ? DEFAULT_OPTION_COUNT : optionCount;
    }

    /** 计算进度百分比：(成功+失败)/总数，封顶 100% */
    private int calculateProgress(int successCount, int failedCount, int totalCount) {
        if (totalCount <= 0) {
            return 100;
        }
        return Math.min(100, (successCount + failedCount) * 100 / totalCount);
    }

    /** 根据成功/失败数确定任务终态：全成功=success，部分成功=partial_success，全失败=failed */
    private String resolveTerminalStatus(int successCount, int failedCount) {
        if (failedCount == 0) {
            return "success";
        }
        return successCount > 0 ? "partial_success" : "failed";
    }

    /** 文本兜底：为 null 或空时使用默认值，否则去除首尾空白 */
    private String trimTextOrDefault(String topic, String defaultTopic) {
        if (topic == null || topic.isBlank()) {
            return defaultTopic;
        }
        return topic.strip();
    }

    /** 拼接题目唯一编码：课程名-题型-序号-题目ID */
    private String buildQuestionCode(String courseName, String questionType, int sequence, Long questionId) {
        String normalizedCourse = courseName == null ? "course" : courseName.strip().replaceAll("\\s+", "-");
        return normalizedCourse + "-" + questionType + "-" + sequence + "-" + questionId;
    }

    /** 安全发送 SSE 事件：连接已断开时静默跳过 */
    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(JSON.toJSONString(data)));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送已跳过: event={}, reason={}", name, e.getMessage());
        }
    }

    /** 正常结束 SSE 连接 */
    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("SSE 完成已跳过: {}", e.getMessage());
        }
    }

    /** 以错误方式结束 SSE 连接 */
    private void completeEmitterWithError(SseEmitter emitter, Exception error) {
        try {
            emitter.completeWithError(error);
        } catch (Exception e) {
            log.debug("SSE 错误完成已跳过: {}", e.getMessage());
        }
    }

    /**
     * 解析大模型响应文本为题目 JSON 数组。
     *
     * <p>兼容大模型在 JSON 外包 ```json 代码块围栏的情况；最终从根节点的 {@code questions} 数组提取题目。</p>
     */
    private JsonNode parseLlmResponse(String response) {
        if (response == null) {
            throw new BusinessException("LLM响应为空");
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
                throw new BusinessException("LLM响应JSON缺少questions数组");
            }
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", response, e);
            throw new BusinessException("解析LLM响应为有效JSON失败");
        }
    }

    /** 从 JSON 节点读取文本字段，缺失或为空时返回默认值 */
    private String readText(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }

        String text = value.asText();
        return text == null || text.isBlank() ? defaultValue : text;
    }

    /** 从 JSON 节点读取整数字段，兼容数字与字符串两种形态，异常时返回默认值 */
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

    /** 读取并转换 JSON 数组字段为 List<Map>，非数组则抛异常 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJsonArrayAsList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }

        if (!value.isArray()) {
            throw new BusinessException("字段fieldName " + fieldName + " 必须是JSON数组");
        }

        return objectMapper.convertValue(value, List.class);
    }

    /** 读取并转换 JSON 数组字段为 List<String>，非数组则抛异常 */
    @SuppressWarnings("unchecked")
    private List<String> readJsonArrayAsStringList(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return List.of();
        }

        if (!value.isArray()) {
            throw new BusinessException("字段fieldName " + fieldName + " 必须是JSON数组");
        }

        return objectMapper.convertValue(value, List.class);
    }

    /** 读取并转换 JSON 对象字段为 Map，非对象则抛异常 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonObjectAsMap(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return Map.of();
        }

        if (!value.isObject()) {
            throw new BusinessException("字段fieldName " + fieldName + " 必须是JSON对象");
        }

        return objectMapper.convertValue(value, Map.class);
    }

    /** 读取必填文本字段，缺失或为空时抛业务异常（大模型题目校验） */
    private String readRequiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            throw new BusinessException("LLM题目缺少必填字段fieldName: " + fieldName);
        }

        String text = value.asText();
        if (text == null || text.isBlank()) {
            throw new BusinessException("LLM题目字段fieldName为空: " + fieldName);
        }

        return text;
    }

    /** 生成上下文：贯穿一次生成任务的请求参数、课程/主题、任务(job)与明细(item)集合 */
    private record GenerationContext(
            GenerateQuestionDTO dto,
            String courseName,
            String topic,
            EvaluationQuestionGenerationJob job,
            List<EvaluationQuestionGenerationItem> items
    ) {
    }

    /** 生成批次：同一题型的一组待生成明细，可独立并行执行 */
    private record GenerationBatch(
            String questionType,
            int count,
            int difficulty,
            int optionCount,
            List<EvaluationQuestionGenerationItem> items
    ) {
    }
}
