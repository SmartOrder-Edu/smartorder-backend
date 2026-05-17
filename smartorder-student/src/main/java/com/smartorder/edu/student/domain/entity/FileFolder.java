package com.smartorder.edu.student.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件夹表
 */
@Data
@NoArgsConstructor
public class FileFolder {

    /** 文件夹ID */
    private Long id;

    /** 父文件夹ID（0=根目录） */
    private Long parentId;

    /** 文件夹名称 --（可能被重命名） */
    private String folderName;

    /** 所属用户ID */
    private Long userId;

    /** 排序权重（越大越靠前，默认0） */
    private Integer sort;

    /** 创建时间 */
    private LocalDateTime createdTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
