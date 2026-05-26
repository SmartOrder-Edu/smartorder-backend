package com.smartorder.edu.manage.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 反馈实体类
 */
@Data
@NoArgsConstructor
@TableName("feedback")
public class Feedback {
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 反馈类型：1=体验评价，2=功能建议，3=Bug反馈，4=其他意见
     */
    private Integer type;

    /**
     * 反馈标题
     */
    private String title;
    
    /**
     * 反馈内容
     */
    private String content;
    
    /**
     * 图片URLs（逗号分隔或JSON）
     */
    private String imageUrls;

    /**
     * 联系方式
     */
    private String contact;
    
    /**
     * 审核状态：0=待AI初审，1=AI审核通过（待人工复核），2=AI审核驳回，3=人工审核通过（有效反馈），4=人工审核驳回，5=已采纳
     */
    private Integer status;
    
    /**
     * AI初审意见/分析结果
     */
    private String aiAnalysisResult;
    
    /**
     * 驳回理由或人工审核备注
     */
    private String reviewRemark;

    /**
     * 管理员答复内容
     */
    private String replyContent;

    /**
     * 管理员答复时间
     */
    private LocalDateTime replyTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 是否逻辑删除：0=未删除，1=已删除
     */
    @TableLogic
    private Integer isDeleted;
}
