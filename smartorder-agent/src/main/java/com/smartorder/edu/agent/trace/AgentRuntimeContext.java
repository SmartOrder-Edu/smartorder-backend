package com.smartorder.edu.agent.trace;

import com.smartorder.edu.agent.orchestrator.ExecutionBudget;
import com.smartorder.edu.agent.route.AgentRunMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 运行时上下文——一次完整 Agent 调用的生命周期状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRuntimeContext {

    private String traceId;

    private String conversationId;

    /**
     * 会话主表内部主键（conversation.id），仅用于服务端关联，不对外
     */
    private Long conversationPk;

    /**
     * 本轮轮次ID（当前实现等于 traceId），用于把"一次请求"和"一次生成"绑定
     */
    private String turnId;

    /**
     * 本轮 assistant 占位行ID（conversation_message.id），done 事件会带回前端
     */
    private Long assistantMessageId;

    /**
     * 本轮 assistant 占位行序号（conversation_message.sequence_no）
     */
    private Integer assistantSequenceNo;

    private Long userId;

    /**
     * 用户原始消息
     */
    private String message;

    @Builder.Default
    private AgentRunMode mode = AgentRunMode.REACT;

    private String attachmentContext;

    /**
     * RAG 检索上下文（自动触发时生成）
     */
    private String ragContext;

    /**
     * 是否强制调用 ragRetrieve 工具（当前端传递了 fileIds 或 knowledgeBaseIds 时为 true）
     */
    private boolean forceRagRetrieve;

    /**
     * 前端传递的知识库ID列表（用于 RAG 检索）
     */
    private List<Long> knowledgeBaseIds;

    /**
     * 前端传递的文件ID列表（用于 RAG 检索）
     */
    private List<Long> fileIds;

    /**
     * 学习执行页的当前脚本/段落/画布/时间轴上下文。
     */
    private Map<String, Object> studyContext;

    private List<Long> allowedAttachmentFileIds;

    @Builder.Default
    private AtomicInteger sequence = new AtomicInteger(0);

    /**
     * 执行预算——在 ReAct 和 Plan 循环的每次迭代中检查
     */
    private ExecutionBudget budget;

    /**
     * 客户端是否已断开 SSE 连接
     */
    @Builder.Default
    private volatile boolean clientDisconnected = false;

    /**
     * 助手消息是否已保存（防止 AgentRunSupervisor 重复保存）
     */
    @Builder.Default
    private boolean assistantMessageSaved = false;

    /**
     * 当前计划的运行状态（用于 done 事件携带，前端可据此判断是否暂停态）
     */
    private String planRunStatus;

    /**
     * 当前计划草稿 ID（用于 done 事件携带）
     */
    private String planDraftId;

    /**
     * 当前活跃的澄清 ID（暂停时设置）
     */
    private String activeClarificationId;

    /**
     * askUser 工具调用时写入的澄清参数（ReAct 引擎 → PlanAndExecuteEngine 传递）
     * 包含: question, answerType, options(List<{label,value}>), slotKey
     */
    private Map<String, Object> clarificationPayload;

    /**
     * Plan-and-Execute 子任务调用 createLearningPlan 后产生的学习计划草稿 ID。
     * 该草稿由用户在前端审阅确认，确认后才真正创建学习空间。
     */
    private String learningPlanDraftId;

    /**
     * 当前执行流是否已产出学习计划草稿并等待用户确认。
     */
    @Builder.Default
    private boolean learningPlanAwaitingConfirmation = false;

    /**
     * 获取下一个事件序号
     */
    public int nextSeq() {
        return sequence.incrementAndGet();
    }

    /**
     * 当前序号（不自增）
     */
    public int currentSeq() {
        return sequence.get();
    }
}
