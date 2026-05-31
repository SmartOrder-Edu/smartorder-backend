package com.smartorder.edu.common.model.dto;

import org.springframework.data.annotation.PersistenceCreator;

/**
 * Neo4j 关系查询结果 DTO（类投影）
 */
public class RelationshipResult {
    private final String sourceId;
    private final String targetId;
    private final String relationType;
    private final Double weight;
    private final String description;

    @PersistenceCreator
    public RelationshipResult(String sourceId, String targetId, String relationType, Double weight, String description) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.relationType = relationType;
        this.weight = weight;
        this.description = description;
    }

    public String getSourceId() { return sourceId; }
    public String getTargetId() { return targetId; }
    public String getRelationType() { return relationType; }
    public Double getWeight() { return weight; }
    public String getDescription() { return description; }
}
