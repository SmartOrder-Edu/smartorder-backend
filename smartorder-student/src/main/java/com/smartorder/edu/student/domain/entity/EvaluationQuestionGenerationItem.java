package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("evaluation_question_generation_item")
public class EvaluationQuestionGenerationItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long jobId;
    private Long questionId;
    private String questionType;
    private Integer difficulty;
    private Integer optionCount;
    private Integer sequenceNo;
    private String status;
    private Integer retryCount;
    private Integer maxRetryCount;
    private String rawResponse;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
