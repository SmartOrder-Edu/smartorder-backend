package com.smartorder.edu.common.model.entity;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件解析结果封装类
 * 作用：统一存储不同类型文件的解析结果，便于上层调用
 */

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileParseRecord {
    /** 文件名 */
    private String fileName;
    /** 文件类型（MIME） */
    private String fileType;
    /** 内容摘要（格式化后的简短内容） */
    private String contentSummary;
    /** 原始解析内容（完整内容） */
    private String rawContent;
    /** 文件在阿里云OSS中的访问地址 */
    private String ossUrl;
}
