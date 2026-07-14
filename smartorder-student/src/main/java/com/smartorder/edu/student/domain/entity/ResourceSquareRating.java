package com.smartorder.edu.student.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 资源广场评分表
 */
@Data
@NoArgsConstructor
public class ResourceSquareRating {

    /** 评分ID */
    private Long id;

    /** 资源广场ID */
    private Long squareId;

    /** 评分用户ID */
    private Long userId;

    /** 评分：1-5 */
    private Integer rating;

    /** 评分时间 */
    private LocalDateTime createTime;
}
