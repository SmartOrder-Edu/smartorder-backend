package com.smartorder.edu.student.domain.dto;

import lombok.Data;

/**
 * python解析回调DTO类，表示解析完成后的回调数据结构
 */
@Data
public class ParseCallbackDTO {
    /**
     * 解析的文件ID
     */
    private Long fileId;
    /**
     * 解析状态：true-成功，false-失败
     */
    private Boolean status;
    /**
     * 状态消息，描述解析结果的详细信息
     */
    private String message;
    /**
     * 解析结果，通常是一个JSON字符串，包含解析后的数据结构和相关信息
     */
    private String summaryContent;
}
