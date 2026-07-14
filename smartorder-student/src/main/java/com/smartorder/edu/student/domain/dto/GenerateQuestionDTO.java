package com.smartorder.edu.student.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 测评题目生成请求
 */
@Data
public class GenerateQuestionDTO {

    /** 学习空间ID */
    @NotNull(message = "学习空间ID不能为空")
    private Long learningSpaceId;

    /** 章节ID */
    private Long chapterId;

    /** 知识点ID */
    private Long knowledgePointId;

    /** 课程名称，例如：数据结构、数据库 */
    private String courseName;

    /** 出题主题，例如：冒泡排序 */
    private String topic;

    @Valid
    private List<QuestionTypeCountDTO> questionTypeCountList;

    /**
     * 题型数量配置：描述某一题型要生成多少道题、难度与选项数。
     */
    @Data
    public static class QuestionTypeCountDTO {
        /** 题型编码：single_choice/scenario/sql/code 等 */
        private String questionType;
        /** 生成题目数量（1~10） */
        @Min(value = 1, message = "题目数量不能小于1")
        @Max(value = 10, message = "题目数量不能大于10")
        private Integer count;
        /** 难度等级：1-5 */
        @Min(value = 1, message = "难度不能小于1")
        @Max(value = 5, message = "难度不能大于5")
        private Integer difficulty;

        /** 选项数量（仅选择题有效，默认4） */
        private Integer optionCount;

        /** 编程语言（仅 code 题型生效），默认 C */
        private String programmingLanguage;

        /** 排序（可选） */
        private Integer sort;
    }
}
