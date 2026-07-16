package com.smartorder.edu.learning.dto;

import lombok.Data;

@Data
public class PlanTodayTasksDTO {
    /**
     * 本次最多生成任务数。后端会做上下限保护，避免一次生成过多。
     */
    private Integer maxTasks;
}
