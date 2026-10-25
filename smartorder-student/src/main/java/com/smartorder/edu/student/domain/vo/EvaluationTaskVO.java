package com.smartorder.edu.student.domain.vo;

import com.smartorder.edu.student.domain.entity.EvaluationQuestionGenerationTask;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 测评题库列表展示 VO
 *
 * @deprecated 全仓无任何引用。题库列表接口
 * {@code GET /evaluation/questions/task} 已改为直接返回
 * {@link com.smartorder.edu.student.domain.entity.EvaluationSession} 列表，
 * 前端按 session 维度渲染。新代码请复用该实体，本类可在确认后删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@Data
@NoArgsConstructor
public class EvaluationTaskVO {
    /** 任务ID (用于跳转详情/开始测评) */
    private Long id;

    /** 学习空间ID */
    private Long learningSpaceId;

    /** 主标题 (如：内存管理阶段测试) */
    private String topic;

    /** 副标题 (如：分页、分段、虚拟内存) - 可用 courseName 或知识点名称填充 */
    private String description;

    /** 状态：pending/running/success/failed (前端用来判断是否显示"可参与") */
    private String status;

    /** 题目数量 (展示用，如图中的 "15题") */
    private Integer questionCount;

    /** 预计耗时(分钟) - 图中的 "30分钟" */
    private Integer estimatedDuration;

    /** 当前用户的正确率(%) - 图中的 "0% 正确率" (初期可默认返回 0) */
    private Integer accuracy;

    /** 进度 (如果是生成中的任务，可以返回此字段渲染进度条) */
    private Integer progress;

    /** 创建时间 (前端用来排序或展示) */
    private LocalDateTime createdAt;

    /**
     * 从生成任务实体构造视图对象。
     *
     * @param task 生成任务实体
     */
    public EvaluationTaskVO(EvaluationQuestionGenerationTask task) {
        this.id = task.getId();
        this.learningSpaceId = task.getLearningSpaceId();
        this.topic = task.getTopic();
        this.description = task.getLearningName();
        this.status = task.getStatus();
        // 展示已成功生成的题数；若任务仍在跑则显示总题数
        this.questionCount = "success".equals(task.getStatus())
                ? task.getSuccessCount()
                : task.getTotalCount();
        // 预估每题 2 分钟
        this.estimatedDuration = task.getTotalCount() != null ? task.getTotalCount() * 2 : 0;
        this.accuracy = 0;
        this.progress = task.getProgress();
        this.createdAt = task.getCreatedAt();
    }
}