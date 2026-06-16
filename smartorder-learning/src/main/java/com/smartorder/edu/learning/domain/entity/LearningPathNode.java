package com.smartorder.edu.learning.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class LearningPathNode {
    private Long id;
    private Long spaceId;
    /** 父节点 ID：stage 节点为 null；knowledge_point 节点为所属 stage 的 ID */
    private Long parentNodeId;
    /** 知识点全局 UID（与 knowledge_graph_node.kp_uid 共享，仅 knowledge_point 节点必填） */
    private String kpUid;
    private String title;
    private String description;
    private String nodeType;
    private Integer estimatedDurationMinutes;
    /** 计划时长（天） — 草案落库时记录，永久不变 */
    private Integer plannedDurationDays;
    /** 预计时长（天） — 动态调整 */
    private Integer estimatedDurationDays;
    /** 实际时长（天） — 节点完成后回填 */
    private Integer actualDurationDays;
    private Integer difficultyLevel;
    private Integer sortOrder;
    private Integer isMilestone;
    private String iconUrl;
    private String metadata;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
