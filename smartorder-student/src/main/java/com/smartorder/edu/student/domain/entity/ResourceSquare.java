package com.smartorder.edu.student.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资源广场发布表
 */
@Data
@NoArgsConstructor
public class ResourceSquare {

    /** 广场资源ID */
    private Long id;

    /** 资源标题 */
    private String title;

    /** 资源描述 */
    private String description;

    /** 标签，JSON数组格式 */
    private String tags;

    /** 分类：doc/mindmap/video/quiz/case */
    private String category;

    /** 发布者用户ID */
    private Long userId;

    /** 关联的原始文件ID */
    private Long fileId;

    /** 封面图URL */
    private String coverUrl;

    /** 下载/转存次数 */
    private Integer downloadCount;

    /** 浏览次数 */
    private Integer viewCount;

    /** 平均评分 */
    private BigDecimal ratingAvg;

    /** 评分人数 */
    private Integer ratingCount;

    /** 状态：0-已下架，1-已发布 */
    private Integer status;

    /** 逻辑删除：0-未删除，1-已删除 */
    private Integer isDeleted;

    /** 发布时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
