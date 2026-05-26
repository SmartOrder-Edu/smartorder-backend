package com.smartorder.edu.manage.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 反馈展示 VO
 */
@Data
@NoArgsConstructor
public class FeedbackVO {
    private Long id;
    private Long userId;
    private Integer type;
    private String title;
    private String content;
    private List<String> imageUrls;
    private String contact;
    private Integer status;
    private String aiAnalysisResult;
    private String reviewRemark;
    private String replyContent;
    private LocalDateTime replyTime;
    private LocalDateTime createTime;
}
