package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class ResourceContentVO {
    private Long id;
    private Long resourceId;
    private String contentType;
    private String content;
    private Integer version;
    private String status;
    private String reviewResult;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
