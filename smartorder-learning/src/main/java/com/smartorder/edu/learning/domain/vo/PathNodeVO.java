package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PathNodeVO {
    private Long id;
    private Long spaceId;
    /** 父节点 ID：stage 为 null；knowledge_point 指向所属 stage */
    private Long parentNodeId;
    /** 知识点全局 UID（仅 knowledge_point 节点有值） */
    private String kpUid;
    private String title;
    private String description;
    private String nodeType;
    private Integer estimatedDurationMinutes;
    /** 计划时长（天） — 草案落库时的初始预估 */
    private Integer plannedDurationDays;
    private String difficultyLevel;
    private Integer sortOrder;
    private Boolean isMilestone;
    private String status;
}
