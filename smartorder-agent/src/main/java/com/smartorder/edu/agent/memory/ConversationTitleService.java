package com.smartorder.edu.agent.memory;

import com.smartorder.edu.agent.mapper.ConversationMapper;
import com.smartorder.edu.agent.model.entity.Conversation;
import com.smartorder.edu.agent.prompt.PromptLoader;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话标题生成服务——把用户首条消息压成"标签式"短标题。
 *
 * <p><b>为什么异步</b>：标题要调一次 LLM（数百毫秒到数秒），而首条消息落库
 * （{@code ConversationTurnService#openTurn}）是持有 conversation 行锁的事务 A，
 * 绝不能把 LLM 调用塞进事务里；同时 SSE 首包必须立刻返回，用户要马上看到自己的消息。
 * 因此这里用 {@code @Async} 在事务提交之后独立执行，失败也不影响主流程。</p>
 *
 * <p><b>输入</b>：不是原始消息，而是经 {@link TitleInputExtractor} 清洗后的文本——
 * 前端会把 {@code [资源上下文]} 注入块拼在消息前面，不清掉模型就会拿资源标题当会话标题。</p>
 *
 * <p><b>输出</b>：DeepSeek 风格的标签（"Milvus连接超时排查"），不是句子。
 * 模型偶尔会把思考过程或用户原话一起吐出来，{@link TitleNormalizer} 判废后
 * 这里会带着更强的约束重试一次；仍不可用就放弃，保留 AUTO 兜底标题。</p>
 *
 * <p><b>覆盖规则</b>：只有 {@code title_source=AUTO} 才会被覆盖，用户改过名（USER）
 * 或已生成过（LLM）都不再动，由 {@code ConversationMapper#updateTitleIfAuto} 保证。</p>
 */
@Slf4j
@Service
public class ConversationTitleService {

    /**
     * 二次尝试时加在输入前面的约束。
     * <p>首次输出被判废通常是因为模型在"解释"而不是"起标题"，
     * 再把要求压一遍比换提示词更省事（同一个 AiService 实例，不用维护两份 prompt）。</p>
     * <p>包级可见：旧数据回填脚本复用同一约束，避免两处措辞漂移。</p>
     */
    static final String RETRY_PREFIX =
            "只输出一个 6~12 字的名词短语标题，不要解释、不要标点、不要引号。用户消息：\n";

    private final ConversationMapper conversationMapper;
    private final TitleGeneratorAgent titleGeneratorAgent;
    private final TraceEventDispatcher dispatcher;

    public ConversationTitleService(ConversationMapper conversationMapper,
                                    PromptLoader promptLoader,
                                    @Qualifier("titleChatModel") ChatModel titleChatModel,
                                    TraceEventDispatcher dispatcher) {
        this.conversationMapper = conversationMapper;
        this.dispatcher = dispatcher;
        // systemMessageProvider 是惰性 lambda，构建时不会读提示词，运行时才取，无初始化顺序问题
        this.titleGeneratorAgent = AiServices.builder(TitleGeneratorAgent.class)
                .chatModel(titleChatModel)
                .systemMessageProvider(memoryId -> promptLoader.getTitleGeneratorPrompt())
                .build();
    }

    /**
     * 异步生成会话标题：清洗用户消息 → 压缩成标签 → 回写 conversation.title → 推送 SSE。
     *
     * <p>调用方无需处理异常，本方法内部吞掉所有异常并保留兜底标题。</p>
     *
     * @param conversationId 会话 public_id
     * @param userMessage    用户首条消息原文（内部会清洗注入块）
     * @param emitter        当前 SSE 连接，可为 null（非流式入口）
     * @param context        本轮运行上下文，仅用于读取 traceId/userId 与分配事件序号
     */
    @Async("agentAsyncExecutor")
    public void generateAsync(String conversationId, String userMessage,
                              SseEmitter emitter, AgentRuntimeContext context) {
        if (conversationId == null || conversationId.isBlank()
                || userMessage == null || userMessage.isBlank()) {
            log.debug("[Title] 输入为空，跳过标题生成: cid={}", conversationId);
            return;
        }

        // 注入块、Markdown、代码块在这里被剥掉，模型只看到用户真正问的那句话
        String input = TitleInputExtractor.extract(userMessage);
        if (input.isBlank()) {
            // 纯附件消息 / 只有资源上下文且无标题：没有可压缩的文字，保留兜底标题
            log.debug("[Title] 清洗后无有效内容，跳过标题生成: cid={}", conversationId);
            return;
        }

        long start = System.currentTimeMillis();
        try {
            String title = generateTitle(input);
            if (title == null) {
                log.warn("[Title] 模型输出不可用，保留兜底标题: cid={}, input={}",
                        conversationId, abbreviate(input));
                return;
            }

            if (TitleNormalizer.isPlaceholder(title)) {
                // 首条消息确实没内容（"你好"）。不落库：保持 AUTO，下一轮有实质内容时还能再生成
                log.info("[Title] 首条消息无实质内容，暂不落库: cid={}, input={}",
                        conversationId, abbreviate(input));
                return;
            }

            int updated = conversationMapper.updateTitleIfAuto(conversationId, title);
            if (updated == 0) {
                log.info("[Title] 跳过回写（已改名或已生成过）: cid={}, title={}", conversationId, title);
                return;
            }

            log.info("[Title] 标题生成成功: cid={}, title={}, cost={}ms",
                    conversationId, title, System.currentTimeMillis() - start);
            pushTitleEvent(emitter, context, conversationId, title);

        } catch (Exception e) {
            // 标题是锦上添花：失败就保留兜底标题，绝不能影响聊天主流程
            log.warn("[Title] 标题生成失败，保留兜底标题: cid={}, cost={}ms, reason={}",
                    conversationId, System.currentTimeMillis() - start, e.getMessage());
        }
    }

    // ============ Private ============

    /**
     * 调模型拿标题，最多两次：第一次不合格就带着更强的约束再试一次。
     *
     * @return 可用标题；两次都不合格返回 {@code null}
     */
    private String generateTitle(String input) {
        String first = TitleNormalizer.normalize(titleGeneratorAgent.generateTitle(input));
        if (isUsable(first, input)) {
            return first;
        }
        log.debug("[Title] 首次输出不合格: title={}，重试一次", first);

        String retry = TitleNormalizer.normalize(
                titleGeneratorAgent.generateTitle(RETRY_PREFIX + input));
        return isUsable(retry, input) ? retry : null;
    }

    /**
     * 标题是否可用：格式合法（normalize 已保证）+ 不是复读用户原话。
     */
    private boolean isUsable(String title, String input) {
        return title != null && !TitleNormalizer.isEcho(title, input);
    }

    /**
     * 推送标题事件。
     *
     * <p><b>为什么走 emitEphemeral 而不是 emit</b>：标题是异步生成、与主流程无关的
     * “锦上添花”，可能比回答还晚结束。若它进入回放日志，就会出现在 done 之后，
     * 破坏“终态是最后一条事件”的续传语义；因此只发给当前在线连接，不占业务 seq。
     * 没有在线连接时自然丢弃——标题已落库，前端下次拉列表仍能拿到。</p>
     */
    private void pushTitleEvent(SseEmitter emitter, AgentRuntimeContext context,
                                String conversationId, String title) {
        if (context == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("title", title);
        payload.put("titleSource", Conversation.TITLE_SOURCE_LLM);
        dispatcher.emitEphemeral(emitter, context, AgentEventType.CONVERSATION_TITLE, payload);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 60) + "...";
    }
}
