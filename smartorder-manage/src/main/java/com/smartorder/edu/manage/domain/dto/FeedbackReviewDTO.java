package com.smartorder.edu.manage.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运营人员审核反馈 DTO
 */
@Data
@NoArgsConstructor
public class FeedbackReviewDTO {

    /**
     * 审核后状态：3=人工审核通过（有效反馈），4=人工审核驳回，5=已采纳
     */
    @NotNull(message = "审核结果状态不能为空")
    private Integer status;

    /**
     * 人工审核备注/驳回理由
     */
    private String reviewRemark;

    /**
     * 管理员正式答复内容（可为空）
     */
    private String replyContent;
}
