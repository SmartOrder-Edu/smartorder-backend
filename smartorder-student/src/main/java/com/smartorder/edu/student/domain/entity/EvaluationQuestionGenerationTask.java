package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 测评题目生成任务(task)实体。
 *
 * <p>一次「生成题库」请求对应一条 task 记录，汇总该次任务的进度、成功/失败数与状态，
 * 便于前端或运维追踪。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("evaluation_question_generation_task")
@Builder
public class EvaluationQuestionGenerationTask {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 发起用户ID */
    private Long userId;
    /** 所属学习空间ID */
    private Long learningSpaceId;
    /** 所属章节ID */
    private Long chapterId;
    /** 知识点ID */
    private Long knowledgePointId;
    /** 课程名称 */
    private String courseName;
    /** 出题主题 */
    private String topic;
    /** 原始请求参数JSON（回溯用） */
    private String requestPayload;
    /** 任务状态：pending/running/success/partial_success/failed */
    private String status;
    /** 总题数 */
    private Integer totalCount;
    /** 已成功生成题数 */
    private Integer successCount;
    /** 生成失败题数 */
    private Integer failedCount;
    /** 进度百分比 0-100 */
    private Integer progress;
    /** 当前步骤描述（如 running / generating single_choice / finished） */
    private String currentStep;
    /** 失败错误信息（成功时为空） */
    private String errorMessage;
    /** 开始时间 */
    private LocalDateTime startedAt;
    /** 结束时间 */
    private LocalDateTime finishedAt;
    /** 创建时间 */
    private LocalDateTime createdAt;
    /** 更新时间 */
    private LocalDateTime updatedAt;
}
