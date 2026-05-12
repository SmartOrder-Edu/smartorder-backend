package com.smartorder.edu.student.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class FileFolder {
    private Long id;
    private Long parentId;
    private String folderName;
    private Long userId;
    private LocalDateTime createdTime;
    private LocalDateTime updateTime;
}
