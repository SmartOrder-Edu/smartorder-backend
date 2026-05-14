package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 学习空间数据传输对象
 * @author 谢光湘
 * @since 2026/5/12
 */
@Data
@NoArgsConstructor
public class LearningSpaceDTO {
    /**
     * 空间名称
     */
    private String name;
    /**
     * 空间类型：course/exam/project/career/competition
     */
    private String type;
    /**
     * 空间描述
     */
    private String description;
    /**
     * 图标URL
     */
    private String iconUrl;
    /**
     * 封面图URL
     */
    private String coverUrl;
    /**
     * 目标列表（JSON格式）
     */
    private String goals;
    /**
     * 计划开始日期
     */
    private LocalDate startDate;
    /**
     * 目标完成日期
     */
    private LocalDate targetEndDate;
    /**
     * 用户自定义排序
     */
    private Integer sortOrder;
}
