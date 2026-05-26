package com.smartorder.edu.manage.domain.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 后台查询反馈列表的 DTO
 */
@Data
@NoArgsConstructor
public class FeedbackQueryDTO {

    /**
     * 当前页码
     */
    private Integer page = 1;

    /**
     * 每页数量
     */
    private Integer size = 10;

    /**
     * 反馈类型：1=体验评价，2=功能建议，3=Bug反馈，4=其他意见
     */
    private Integer type;

    /**
     * 审核状态：0=待AI初审，1=AI审核通过（待人工复核），2=AI审核驳回，3=人工审核通过（有效反馈），4=人工审核驳回，5=已采纳
     */
    private Integer status;
}
