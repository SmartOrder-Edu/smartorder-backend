package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.mapper.AgentRunStateMapper;
import com.smartorder.edu.agent.model.entity.AgentRunState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 运行状态服务 —— 管理 Plan-and-Execute 全生命周期的权威状态
 * <p>
 * 状态流转规则：
 * <pre>
 * PLANNING → WAITING_CONFIRMATION  (计划生成完毕，等待确认)
 * PLANNING → PAUSED_CLARIFICATION   (首个任务是澄清)
 * PLANNING → PAUSED_CONFIRMATION    (首个任务是确认)
 * WAITING_CONFIRMATION → EXECUTING  (用户确认)
 * EXECUTING → PAUSED_CLARIFICATION  (遇到澄清门)
 * EXECUTING → PAUSED_CONFIRMATION   (遇到确认门)
 * EXECUTING → COMPLETED             (全部完成)
 * EXECUTING → FAILED                (不可恢复失败)
 * PAUSED_CLARIFICATION → EXECUTING  (全部回答完)
 * PAUSED_CONFIRMATION → EXECUTING   (用户确认)
 * 任意非终态 → CANCELLED            (用户取消)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunStateService {

    private final AgentRunStateMapper mapper;

    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private static final Set<String> PAUSED_STATUSES = Set.of("PAUSED_CLARIFICATION", "PAUSED_CONFIRMATION");

    /**
     * 创建新的运行状态（初始 PLANNING）
     */
    public AgentRunState createRunState(String conversationId, String draftId, Long userId) {
        AgentRunState state = new AgentRunState();
        state.setRunId(UUID.randomUUID().toString());
        state.setConversationId(conversationId);
        state.setDraftId(draftId);
        state.setUserId(userId);
        state.setStatus("PLANNING");
        state.setCurrentStepIndex(0);
        mapper.insert(state);
        log.info("[RunState] 创建运行状态: runId={}, convId={}, draftId={}", state.getRunId(), conversationId, draftId);
        return state;
    }

    /**
     * 状态转换——更新运行状态到目标状态，并记录暂停/完成信息
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "mysqlTransactionManager")
    public void transitionTo(String runId, String newStatus) {
        transitionTo(runId, newStatus, null, null, null, null);
    }

    /**
     * 状态转换（带暂停信息）
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "mysqlTransactionManager")
    public void transitionTo(String runId, String newStatus, String traceId,
                              String pausedReason, String pausedTaskId, String pausedClarificationId) {
        AgentRunState state = mapper.selectByRunId(runId);
        if (state == null) {
            log.warn("[RunState] 未找到运行状态: runId={}", runId);
            return;
        }

        String oldStatus = state.getStatus();
        if (TERMINAL_STATUSES.contains(oldStatus)) {
            log.warn("[RunState] 终态不允许转换: runId={}, {} → {}", runId, oldStatus, newStatus);
            return;
        }

        state.setStatus(newStatus);
        if (traceId != null) state.setTraceId(traceId);
        if (pausedReason != null) state.setPausedReason(pausedReason);
        if (pausedTaskId != null) state.setPausedTaskId(pausedTaskId);
        if (pausedClarificationId != null) state.setPausedClarificationId(pausedClarificationId);

        // PAUSED_* 状态保留 paused 字段；非暂停态清除它们
        if (!PAUSED_STATUSES.contains(newStatus)) {
            state.setPausedReason(null);
            state.setPausedTaskId(null);
            state.setPausedClarificationId(null);
            state.setPausedQuestion(null);
        }

        if ("COMPLETED".equals(newStatus) || "FAILED".equals(newStatus) || "CANCELLED".equals(newStatus)) {
            state.setCompletedAt(LocalDateTime.now());
        }

        state.setUpdateTime(LocalDateTime.now());
        mapper.updateById(state);
        log.info("[RunState] 状态转换: runId={}, {} → {}", runId, oldStatus, newStatus);
    }

    /**
     * 更新当前步骤索引
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "mysqlTransactionManager")
    public void updateStepIndex(String runId, int stepIndex) {
        AgentRunState state = mapper.selectByRunId(runId);
        if (state != null && !TERMINAL_STATUSES.contains(state.getStatus())) {
            state.setCurrentStepIndex(stepIndex);
            state.setUpdateTime(LocalDateTime.now());
            mapper.updateById(state);
        }
    }

    /**
     * 查询会话的最新活跃运行（非终态）
     */
    public AgentRunState getActiveRun(String conversationId) {
        return mapper.selectActiveByConversationId(conversationId);
    }

    /**
     * 根据 runId 查询
     */
    public AgentRunState getByRunId(String runId) {
        return mapper.selectByRunId(runId);
    }

    /**
     * 根据 draftId 查询
     */
    public AgentRunState getByDraftId(String draftId) {
        return mapper.selectByDraftId(draftId);
    }

    /**
     * 按 draftId 进行状态转换（引擎内部常用 draftId，而非 runId）
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "mysqlTransactionManager")
    public void transitionByDraftId(String draftId, String newStatus, String traceId,
                                     String pausedReason, String pausedTaskId, String pausedClarificationId) {
        // 使用 FOR UPDATE 行锁防止并发状态转换
        AgentRunState state = mapper.selectByDraftIdForUpdate(draftId);
        if (state == null) {
            log.warn("[RunState] draftId 对应的 runState 不存在: draftId={}", draftId);
            return;
        }
        transitionTo(state.getRunId(), newStatus, traceId, pausedReason, pausedTaskId, pausedClarificationId);
    }

    /**
     * 按 draftId 进行简单状态转换（无暂停信息）
     */
    @Transactional(rollbackFor = Exception.class, transactionManager = "mysqlTransactionManager")
    public void transitionByDraftId(String draftId, String newStatus) {
        transitionByDraftId(draftId, newStatus, null, null, null, null);
    }
}
