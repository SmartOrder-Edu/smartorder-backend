package com.smartorder.edu.agent.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务列表——Plan-then-ReAct 架构中规划与执行的桥梁
 * <p>
 * Plan 阶段产出 TaskList，Execute 阶段遍历 TaskList 逐任务执行。
 * 每个 TaskItem 携带 requiredTools、dependsOn、riskLevel，
 * 执行器据此决定工具集、执行顺序、确认门。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskList {

    /** 用户原始目标 */
    private String userGoal;

    /** 计划 ID（UUID，保存时生成） */
    private String planId;

    /** 所有任务（按拓扑排序后的顺序） */
    private List<TaskItem> tasks;

    /**
     * 整体状态：
     * DRAFT → EXECUTING → PAUSED_CLARIFICATION / PAUSED_CONFIRMATION → COMPLETED / FAILED / CANCELLED
     */
    @Builder.Default
    private String status = "DRAFT";

    /** 当前执行到的任务索引（断点恢复用） */
    @Builder.Default
    private int currentTaskIndex = 0;

    /** 已完成任务的输出（key=taskId, value=结果摘要），注入后续任务上下文 */
    @Builder.Default
    private Map<String, String> taskOutputs = new LinkedHashMap<>();

    /** 创建时间戳 */
    private Long createdAt;

    /** PAUSED_CLARIFICATION 时填充的追问文本 */
    private String clarificationQuestion;

    /** PAUSED_CONFIRMATION 时填充的草稿 ID */
    private String confirmationDraftId;
}
