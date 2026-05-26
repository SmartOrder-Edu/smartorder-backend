package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 测评题库实体
 */
@Data
@NoArgsConstructor
@TableName(value = "evaluation_question", autoResultMap = true)
public class EvaluationQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String questionCode;

    private Long learningSpaceId;

    private Long chapterId;

    private Long knowledgePointId;

    private String questionType;

    private Integer difficulty;

    private String abilityDimension;

    private String stem;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> optionsJson;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> answerJson;

    private String explanation;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> commonMistakesJson;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tagsJson;

    private String generationSource;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
