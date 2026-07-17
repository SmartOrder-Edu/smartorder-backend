package com.smartorder.edu.learning.dto;

import lombok.Data;

import java.util.List;

@Data
public class PlanTodayTasksDTO {
    /**
     * 本次最多生成任务数。后端会做上下限保护，避免一次生成过多。
     */
    private Integer maxTasks;

    /**
     * 今日可用学习时间，单位分钟。为空时按任务数量使用默认预算。
     */
    private Integer totalAvailableMinutes;

    /**
     * 编排策略：deadline_first/weakness_first/balanced。
     */
    private String strategy;

    /**
     * 允许生成的任务动作类型。当前 learning 模块只会直接生成 learn_script；
     * 练习/测评由 agent 模块调用测评服务生成真实 session 后再创建任务。
     */
    private List<String> allowedActionTypes;
}
