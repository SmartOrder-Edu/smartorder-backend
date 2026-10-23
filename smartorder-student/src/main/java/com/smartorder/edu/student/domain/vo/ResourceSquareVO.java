package com.smartorder.edu.student.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 资源广场响应 VO
 */
@Data
public class ResourceSquareVO {

    private Long id;

    private String title;

    private String description;

    /** 标签列表（已从JSON字符串解析） */
    private List<String> tags;

    private String category;

    private Long userId;

    /** 发布者姓名 */
    private String publisherName;

    private Long fileId;

    private String coverUrl;

    private Integer downloadCount;

    private Integer viewCount;

    private BigDecimal ratingAvg;

    private Integer ratingCount;

    private Integer status;

    /** 关联文件的类型（从 file_resource 获取） */
    private String fileType;

    /** 关联文件的大小（从 file_resource 获取） */
    private Long fileSize;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 当前用户是否已转存 */
    private Boolean hasDownloaded;

    /** 当前用户的评分（null 表示未评分） */
    private Integer userRating;
}
