package com.smartorder.edu.knowledge.domain.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
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

    @Id
    private String id;

    @TargetNode
    private Neo4jKnowledgeNode targetNode;

    private String relationType;

    private Double weight;

    private Long spaceId;

    public Neo4jKnowledgeRelation(String id, Neo4jKnowledgeNode targetNode, 
                                  String relationType, Double weight, Long spaceId) {
        this.id = id;
        this.targetNode = targetNode;
        this.relationType = relationType;
        this.weight = weight;
        this.spaceId = spaceId;
    }
}