package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserFileQuota {
    @TableId(value = "user_id", type = IdType.ASSIGN_ID)
    private Long userId;
    private Long userSpace;
    private Integer version;
}
