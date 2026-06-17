package com.smartorder.edu.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 活跃计划运行态 VO——前端加载历史会话时，用于恢复 agentStore 中的计划相关状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveRunVO {

    private String draftId;

    private String planType;

    /**
     * 计划状态：DRAFT / PENDING_CONFIRM / EXECUTING / PAUSED_CLARIFICATION / PAUSED_CONFIRMATION
     */
    private String status;

    /**
     * 计划的任务列表（与 PLAN_CREATED 事件的 taskList 结构一致）
     */
    private TaskListVO taskList;

    /**
     * 暂停信息（当 status 为 PAUSED_* 时非空）
     */
    private PauseInfoVO pauseInfo;

    /**
     * 活跃澄清 ID
     */
    private String activeClarificationId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskListVO {
        private String userGoal;
        private String planId;
        private List<TaskItemVO> tasks;
        private String status;
        private Integer currentTaskIndex;
        private Map<String, String> taskOutputs;
        private Long createdAt;
        private String clarificationQuestion;
        private String confirmationDraftId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskItemVO {
        private String taskId;
        private String description;
        private List<String> requiredTools;
        private List<String> dependsOn;
        private String gate;
        private String riskLevel;
        private String expectedOutput;
        private String status;
        private String resultSummary;
        private String errorMessage;
        private Integer retryCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PauseInfoVO {
        private String reason;
        private String taskId;
        private String question;
        private ClarificationInfoVO clarification;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClarificationInfoVO {
        private String clarificationId;
        private String slotKey;
        private String question;
        private String answerType;
        private List<Map<String, String>> options;
        private Integer slotPriority;
    }
}
