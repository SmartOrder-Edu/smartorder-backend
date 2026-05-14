package com.smartorder.edu.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * knowledge_file_jobs 实体类
 */
@Data
@TableName("knowledge_file_jobs")
public class KnowledgeFileJob {

    /**
     * 文件处理任务ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 所属知识库ID
     */
    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    /**
     * 需要处理的文件ID
     */
    @TableField("file_id")
    private Long fileId;

    /**
     * 任务类型：parse=解析，chunk=分块，embed=向量化，parse_chunk_embed=解析分块并向量化
     */
    @TableField("job_type")
    private String jobType;

    /**
     * 任务状态：pending=等待执行，running=执行中，success=成功，failed=失败
     */
    @TableField("status")
    private String status;

    /**
     * 任务进度，范围0到100
     */
    @TableField("progress")
    private Integer progress;

    /**
     * 当前处理步骤，例如：解析中、分块中、向量化中
     */
    @TableField("current_step")
    private String currentStep;

    /**
     * 任务失败时的错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 任务开始时间
     */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /**
     * 任务结束时间
     */
    @TableField("finished_at")
    private LocalDateTime finishedAt;

    /**
     * 任务创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
