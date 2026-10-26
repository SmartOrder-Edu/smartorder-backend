package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.mapper.ConversationMapper;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.util.TitleInputExtractor;
import com.smartorder.edu.agent.util.TitleNormalizer;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
public interface ConversationTitleService {

    void generateAsync(String conversationId, String userMessage, SseEmitter emitter, AgentRuntimeContext context);
}
