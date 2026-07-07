package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 测评Agent题库实体
 *
 * @author 马强
 * @since 2026-05-27
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_question", autoResultMap = true)
public class EvaluationQuestion {

    /** 题目ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 题目唯一编码 */
    private String questionCode;

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 章节ID */
    private Long chapterId;

    /** 知识点ID */
    private Long knowledgePointId;

    /** 题型：single_choice/multiple_choice/judge/fill_blank/short_answer/code/scenario/sql/multimodal */
    private String questionType;

    /** 难度等级：1-10 */
    private Integer difficulty;

    /** 题目分值 */
    private BigDecimal score;

    /** 能力维度：concept/application/analysis/design/debug */
    private String abilityDimension;

    /** 题干 */
    private String stem;

    /** 选项JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> optionsJson;

    /** 标准答案JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> answerJson;

    /** 题目解析 */
    private String explanation;

    /** 常见错误分析JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> commonMistakesJson;

    /** 标签JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tagsJson;

    /** 知识点标签JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> knowledgePointsJson;

    /** 能力标签JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> abilityTagsJson;

    /** 题目分析JSON（包含correct_reason和wrong_reason） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> analysisJson;

    /** 来源：agent_generated/manual/rag/imported */
    private String generationSource;

    /** 状态：1启用，0禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
