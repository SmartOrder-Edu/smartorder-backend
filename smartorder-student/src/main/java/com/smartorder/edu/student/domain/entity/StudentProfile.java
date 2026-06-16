package com.smartorder.edu.student.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.smartorder.edu.student.domain.dto.student.*;
import com.smartorder.edu.student.domain.dto.student.dto.DimensionMetadataDTO;
import com.smartorder.edu.student.domain.dto.student.dto.SubjectProfilesDTO;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 学生动态学习画像实体
 *
 * @author 马强
 * @since 2026-06-15
 */
@Data
@NoArgsConstructor
@TableName(value = "student_profile", autoResultMap = true)
public class StudentProfile {

    /** 学生画像主键ID */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 学生用户ID */
    private Long userId;

    /** 整份学生画像版本号，每次有效更新后递增 */
    private Integer profileVersion;

    /** 学习背景：学历、年级、专业、已修课程、基础能力及学习环境 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private LearningBackgroundDTO learningBackground;

    /** 全局学习目标：长期目标、阶段目标、优先级、截止时间及时间预算 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private LearningGoalsDTO learningGoals;

    /** 综合能力：概念理解、问题分析、算法设计、代码实现、调试及知识迁移能力 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private AbilityProfileDTO abilityProfile;

    /** 跨科目共性易错模式：重复出现的错误习惯、可能原因、影响范围及干预建议 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private CommonMisconceptionsDTO commonMisconceptions;

    /** 稳定学习偏好：资源形式、讲解方式、内容粒度、练习和反馈方式 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private LearningPreferenceDTO learningPreference;

    /** 元认知画像：学习规划、自我评估、反思、主动求助及信心校准能力 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private MetacognitiveProfileDTO metacognitiveProfile;

    /** 各科目画像集合：包含科目目标、知识掌握、易错点、科目偏好、学习行为和当前状态 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private SubjectProfilesDTO subjectProfiles;

    /** 各全局画像维度的数据来源、证据数量、置信度、更新时间及有效期限 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private DimensionMetadataDTO dimensionMetadata;

    /** 当前学习状态 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private CurrentLearningStateDTO currentLearningState;

    /** 根据结构化画像生成的自然语言摘要，仅作为大模型上下文缓存 */
    private String profileSummary;

    /** 整份学生画像完整度，范围0到100 */
    private BigDecimal profileCompleteness;

    /** 整份学生画像可信度，范围0到100 */
    private BigDecimal profileConfidenceScore;

    /** 最近一次画像更新原因 */
    private String lastUpdateReason;

    /** 最近一次系统性重新评估画像的时间 */
    private LocalDateTime lastAssessedAt;

    /** 画像创建时间 */
    private LocalDateTime createdAt;

    /** 画像最近更新时间 */
    private LocalDateTime updatedAt;

    @TableLogic(value = "0", delval = "1")
    private Integer isDeleted;
}
