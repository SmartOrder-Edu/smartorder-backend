package com.smartorder.edu.manage.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 奖品卡密展示 VO
 */
@Data
@NoArgsConstructor
public class PrizeVO {
    private Long id;
    private String name;
    private String prizeCode;
    private Integer status;
    private Long userId;
    private Long feedbackId;
    
    /**
     * 关联展示用：中奖人名称 (如邮箱/用户名)
     */
    private String username;
    
    /**
     * 关联展示用：获得的反馈标题或反馈简述
     */
    private String feedbackTitle;
    
    private LocalDateTime allocateTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
