package com.smartorder.edu.common.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

/**
 * Neo4j 知识关系实体（关系属性）
 */
@Data
@NoArgsConstructor
@RelationshipProperties
public class Neo4jKnowledgeRelation {

    @Id @GeneratedValue
    private Long internalId;

    private String relationId;

    @TargetNode
    private Neo4jKnowledgeNode targetNode;

    private String relationType;

    private Double weight;

    private Long spaceId;

    private String description;

    public Neo4jKnowledgeRelation(String relationId, Neo4jKnowledgeNode targetNode,
                                  String relationType, Double weight, Long spaceId, String description) {
        this.relationId = relationId;
        this.targetNode = targetNode;
        this.relationType = relationType;
        this.weight = weight;
        this.spaceId = spaceId;
        this.description = description;
    }
}
