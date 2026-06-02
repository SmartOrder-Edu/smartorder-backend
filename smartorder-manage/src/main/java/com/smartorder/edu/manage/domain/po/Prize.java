package com.smartorder.edu.manage.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 奖品卡密库实体类
 */
@Data
@NoArgsConstructor
@TableName("prize")
public class Prize {
    /**
     * 奖品ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 奖品名称
     */
    private String name;
    
    /**
     * 奖品兑换码
     */
    private String prizeCode;
    
    /**
     * 状态：0=未发放，1=已发放，2=已废弃
     */
    private Integer status;
    
    /**
     * 获得该奖品的用户ID
     */
    private Long userId;
    
    /**
     * 关联的反馈记录ID
     */
    private Long feedbackId;
    
    /**
     * 发放时间
     */
    private LocalDateTime allocateTime;
    
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
