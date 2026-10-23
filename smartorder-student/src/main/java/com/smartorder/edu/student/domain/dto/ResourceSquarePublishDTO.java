package com.smartorder.edu.student.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发布到资源广场的请求 DTO
 */
@Data
public class ResourceSquarePublishDTO {

    @NotNull(message = "文件ID不能为空")
    private Long fileId;

    @NotBlank(message = "标题不能为空")
    private String title;

    private String description;

    /** 标签，逗号分隔或JSON数组字符串 */
    private String tags;

    @NotBlank(message = "分类不能为空")
    private String category;
}
