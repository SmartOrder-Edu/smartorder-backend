package com.smartorder.edu.student.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 批量提交答案请求 DTO
 *
 * @author 马强
 * @since 2026-07-07
 */
@Data
public class SubmitAnswersDTO {

    /** 答案列表 */
    @NotEmpty(message = "答案列表不能为空")
    @Valid
    private List<AnswerItem> answers;

    @Data
    public static class AnswerItem {
        /** 题目ID */
        @NotNull(message = "题目ID不能为空")
        private Long questionId;

        /** 用户答案（JSON结构，根据题型不同而不同） */
        private Map<String, Object> userAnswer;

        /** 作答耗时（秒） */
        private Integer answerDurationSeconds;

        /** 是否使用提示 */
        private Integer usedHint;
    }
}
