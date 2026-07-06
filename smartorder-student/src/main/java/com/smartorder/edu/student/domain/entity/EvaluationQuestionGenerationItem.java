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
 * 测评题目生成明细(item)实体。
 *
 * <p>job 下每道待生成题目对应一条 item。与题库题目通过 {@code questionId} 关联，
 * 记录单题的生成状态、重试次数与原始大模型响应，便于失败重试与问题排查。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("evaluation_question_generation_item")
public class EvaluationQuestionGenerationItem {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属生成任务ID */
    private Long jobId;
    /** 关联生成的题库题目ID（成功落库后回填） */
    private Long questionId;
    /** 题型 */
    private String questionType;
    /** 难度等级 */
    private Integer difficulty;
    /** 选项数（选择题） */
    private Integer optionCount;
    /** 题目序号 */
    private Integer sequenceNo;
    /** 明细状态：pending/running/success/failed */
    private String status;
    /** 已重试次数 */
    private Integer retryCount;
    /** 最大重试次数 */
    private Integer maxRetryCount;
    /** 大模型原始响应（成功时记录，便于回溯） */
    private String rawResponse;
    /** 错误信息 */
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
