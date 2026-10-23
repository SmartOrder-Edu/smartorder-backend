package com.smartorder.edu.student.domain.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 文件夹导出任务状态（存 Redis 的 JSON 结构）
 *
 * @author SmartOrder
 * @since 2026-07-14
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FolderExportTask {

    /** 任务 ID（Snowflake） */
    private String taskId;

    /** 用户 ID */
    private Long userId;

    /** 待导出的根文件夹 ID */
    private Long folderId;

    /** 根文件夹名称（用于 zip 顶层目录） */
    private String folderName;

    /** 任务状态：PENDING | PROCESSING | COMPLETED | FAILED | CANCELLED */
    private String status;

    /** 文件总数 */
    private int totalFiles;

    /** 已处理文件数 */
    private int processedFiles;

    /** 文件总大小（字节） */
    private long totalBytes;

    /** 已处理字节数 */
    private long processedBytes;

    /** OSS 对象 Key（COMPLETED 后填充） */
    private String ossObjectKey;

    /** 失败原因（FAILED 后填充） */
    private String errorMessage;

    /** 创建时间（ISO 格式） */
    private String createdAt;

    // ==================== 状态常量 ====================
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    // ==================== JSON 序列化 ====================

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化 FolderExportTask 失败", e);
        }
    }

    public static FolderExportTask fromJson(String json) {
        try {
            return MAPPER.readValue(json, FolderExportTask.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("反序列化 FolderExportTask 失败", e);
        }
    }

    /**
     * 计算进度百分比（0-100）
     */
    public int getPercent() {
        if (totalFiles == 0) return 0;
        return Math.min(100, (int) ((processedFiles * 100L) / totalFiles));
    }
}
