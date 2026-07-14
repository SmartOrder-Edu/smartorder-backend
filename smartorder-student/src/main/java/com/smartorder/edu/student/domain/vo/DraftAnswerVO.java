package com.smartorder.edu.student.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 草稿答案视图对象
 *
 * @since 2026-07-11
 */
@Data
@Builder
public class DraftAnswerVO {
    private Long questionId;
    private Map<String, Object> userAnswer;
    private LocalDateTime updatedAt;
}
