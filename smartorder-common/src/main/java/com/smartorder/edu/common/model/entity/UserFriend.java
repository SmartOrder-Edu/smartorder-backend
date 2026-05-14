package com.smartorder.edu.common.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_friend")
public class UserFriend {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long friendId;

    private String friendAlias; // 备注名

    /**
     * 1-正常, 2-单向删除, 3-拉黑
     */
    private Integer status;

    private LocalDateTime createTime;
}