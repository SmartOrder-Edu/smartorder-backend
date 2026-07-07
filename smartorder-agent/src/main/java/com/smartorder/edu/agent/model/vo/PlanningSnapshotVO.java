package com.smartorder.edu.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 会话级目标规划快照。
 * <p>
 * 用于前端历史会话和实时会话恢复唯一执行面板，避免把执行计划状态挂在多条聊天消息上。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanningSnapshotVO {

    private String conversationId;

    private String runId;

    private String executionDraftId;

    private String learningPlanDraftId;

    private String planType;

    private String status;

    private ActiveRunVO.TaskListVO taskList;

    private ActiveRunVO.PauseInfoVO pauseInfo;

    private LearningPlanDraftVO learningPlanDraft;

    private ActiveRunVO.SpaceCreatedVO spaceCreated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LearningPlanDraftVO {
        private String draftId;
        private String spaceName;
        private String type;
        private String description;
        private List<String> goals;
        private String startDate;
        private String targetEndDate;
        private List<Map<String, Object>> phases;
        private Map<String, Object> knowledgeGraph;
    }
}
