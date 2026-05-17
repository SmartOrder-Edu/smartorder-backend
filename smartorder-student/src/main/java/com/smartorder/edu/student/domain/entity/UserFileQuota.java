package com.smartorder.edu.student.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserFileQuota {
    private Long userId;
    private Long userSpace;
    private Integer version;
}
