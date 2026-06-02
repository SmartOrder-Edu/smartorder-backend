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
@TableName("evaluation_question_generation_job")
@Builder
public class EvaluationQuestionGenerationJob {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String jobNo;
    private Long userId;
    private Long learningSpaceId;
    private Long chapterId;
    private Long knowledgePointId;
    private String courseName;
    private String topic;
    private String requestPayload;
    private String status;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private Integer progress;
    private String currentStep;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
