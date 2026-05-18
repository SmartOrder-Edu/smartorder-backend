package com.smartorder.edu.common.model.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j 知识节点实体
 */
@Data
@NoArgsConstructor
@Node("KnowledgeNode")
public class Neo4jKnowledgeNode {

    @Id
    private String id;

    @Property("space_id")
    private Long spaceId;

    @Property("name")
    private String name;

    @Property("description")
    private String description;

    @Property("category")
    private String category;

    @Property("importance")
    private Integer importance;

    @Property("mysql_id")
    private Long mysqlId;

    @Property("file_id")
    private Long fileId;

    @Property("knowledge_base_id")
    private Long knowledgeBaseId;

    @Relationship(type = "PREREQUISITE_OF", direction = Relationship.Direction.OUTGOING)
    private Set<Neo4jKnowledgeNode> prerequisites = new HashSet<>();

    @Relationship(type = "RELATED_TO")
    private Set<Neo4jKnowledgeNode> relatedNodes = new HashSet<>();

    @Relationship(type = "PART_OF", direction = Relationship.Direction.OUTGOING)
    private Set<Neo4jKnowledgeNode> parentNodes = new HashSet<>();

    public Neo4jKnowledgeNode(String id, Long spaceId, String name, String description,
                              String category, Integer importance, Long mysqlId) {
        this.id = id;
        this.spaceId = spaceId;
        this.name = name;
        this.description = description;
        this.category = category;
        this.importance = importance;
        this.mysqlId = mysqlId;
    }
}
