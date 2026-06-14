package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class SpaceDashboardVO {
    private LearningSpaceVO space;
    private String currentGoal;
    private List<TaskVO> todayTasks = new ArrayList<>();
    private List<TaskVO> pendingTasks = new ArrayList<>();
    private List<TaskVO> overdueTasks = new ArrayList<>();
    private List<PathNodeVO> pathNodes = new ArrayList<>();
    private List<PathEdgeVO> pathEdges = new ArrayList<>();
    private PathProgress pathProgress;
    private List<ResourceVO> resources = new ArrayList<>();
    private List<LearningRecordVO> recentRecords = new ArrayList<>();
    private String agentInsight;
    private String riskStatus;
    private Integer streakDays;
    private Double progress;
    private Long daysRemaining;
    private List<AgentActionVO> recentAgentActions = new ArrayList<>();
}
