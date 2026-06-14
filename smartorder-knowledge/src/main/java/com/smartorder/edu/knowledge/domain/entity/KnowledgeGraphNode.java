package com.smartorder.edu.knowledge.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class KnowledgeGraphNode {
    private Long id;
    private Long spaceId;
    private String name;
    /** 知识点全局 UID（与 learning_path_node.kp_uid 一致） */
    private String kpUid;
    private String description;
    private String category;
    private Integer importance;
    private String metadata;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
