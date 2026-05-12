package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学习空间视图对象
 * @author 谢光湘
 * @since 2026/5/12
 */
@Data
@NoArgsConstructor
public class LearningSpaceVO {
    private Long id;
    private Long userId;
    private String name;
    private String type;
    private String description;
    private String iconUrl;
    private String coverUrl;
    private String goals;
    private Double progress;
    private String status;
    private LocalDate startDate;
    private LocalDate targetEndDate;
    private LocalDate actualEndDate;
    private Integer sortOrder;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
