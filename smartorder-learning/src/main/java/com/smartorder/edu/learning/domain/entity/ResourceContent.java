package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class ResourceContent {
    private Long id;
    private Long resourceId;
    private String contentType;
    private String content;
    private Integer version;
    private String status;
    private String reviewResult;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
