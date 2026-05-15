package com.smartorder.edu.student.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileResourceVO {
    /**
     * 文件ID
     */
    private Long fileId;
    /**
     * 文件名称
     */
    private String fileName;
    /**
     * 文件URL
     */
    private String fileUrl;
    /**
     * 文件大小，单位字节
     */
    private Long fileSize;
    /**
     * 文件类型，如pdf、docx等
     */
    private String fileType;
    /**
     * 解析状态，0表示未解析，1表示解析中，2表示解析成功，3表示解析失败
     */
    private Integer parseStatus;
    /**
     * 资源来源，1-原创，2-AI生成，3-广场转存
     */
    private Integer source;
    /**
     * 修改时间
     */
    private LocalDateTime updateTime;
}
