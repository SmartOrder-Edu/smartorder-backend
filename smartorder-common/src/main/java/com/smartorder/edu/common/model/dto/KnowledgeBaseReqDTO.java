package com.smartorder.edu.common.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeBaseReqDTO {
    private Long id;

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    @NotBlank(message = "知识库分类不能为空")
    private String category;

    private String description;

    /**
     * 前端先调用上传接口获取到的封面URL
     */
    private String coverUrl;


}
