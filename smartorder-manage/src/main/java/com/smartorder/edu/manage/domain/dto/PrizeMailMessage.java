package com.smartorder.edu.manage.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 奖品派发异步邮件消息模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrizeMailMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    /**
     * 关联的反馈ID
     */
    private Long feedbackId;
    
    /**
     * 获得者用户ID
     */
    private Long userId;
    
    /**
     * 奖品名称（如：系统 50 积分）
     */
    private String prizeName;
    
    /**
     * 卡密兑换码
     */
    private String prizeCode;
    
    /**
     * 联系方式（用于发送邮件，如 contact 字段或者是 user 绑定的邮箱）
     */
    private String email;
}
