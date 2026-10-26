package com.smartorder.edu.agent.model.dto;

import com.smartorder.edu.agent.model.enums.AgentRunMode;
import com.smartorder.edu.agent.runtime.ExecutionBudget;
import com.smartorder.edu.agent.service.impl.AgentRunSupervisor;
import com.smartorder.edu.agent.service.impl.PlanAndExecuteEngine;
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
    /** 可恢复任务 ID；旧版直接 SSE 调用为空。 */
    private String taskId;

    /**
     * 本次运行所属的生成任务 ID。
     * <p>非空表示事件走“生成日志 + 订阅回放”链路：连接断开不会终止生成，
     * 客户端可以用 seq 游标重连补发。为空表示旧入口（计划确认等），
     * 事件直接写给当前 SSE 连接。</p>
     */
    private String generationId;

    private String conversationId;

    /**
     * 会话主表内部主键（conversation.id），仅用于服务端关联，不对外
     */
    private Long conversationPk;

    /**
     * 本轮追踪ID（当前实现等于 traceId），用于把"一次请求"和"一次生成"绑定
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
     * 客户端是否已断开 SSE 连接。
     * <p>只对旧入口（{@link #generationId} 为空）具有“停止执行”的语义；
     * 生成任务模式下断连只代表订阅中断，任务会继续跑到预算/时限或用户显式取消。</p>
     */
    @Builder.Default
    private volatile boolean clientDisconnected = false;

    /**
     * 用户是否已请求取消本次生成（协作式取消）。
     */
    @Builder.Default
    private volatile boolean cancelRequested = false;

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

    /**
     * 引擎循环是否应该提前退出。
     *
     * <p>生成任务模式下只有“用户取消”会停止执行；连接断开不再等同于取消——
     * 否则用户网络抖动 10 秒，一次已经跑了 40 秒的回答就白费了。</p>
     */
    public boolean shouldStop() {
        if (cancelRequested) {
            return true;
        }
        return generationId == null && clientDisconnected;
    }
}
