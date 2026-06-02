package com.smartorder.edu.common.mapper;

import com.smartorder.edu.common.model.dto.RelationshipResult;
import com.smartorder.edu.common.model.entity.Neo4jKnowledgeNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Neo4j 知识节点仓库接口
 */
@Repository
public interface Neo4jKnowledgeNodeRepository extends Neo4jRepository<Neo4jKnowledgeNode, String> {

    /**
     * 根据空间ID查找所有节点
     */
    List<Neo4jKnowledgeNode> findBySpaceId(Long spaceId);

    /**
     * 根据知识库ID查找所有节点
     */
    List<Neo4jKnowledgeNode> findByKnowledgeBaseId(Long knowledgeBaseId);

    /**
     * 根据文件ID查找所有节点
     */
    List<Neo4jKnowledgeNode> findByFileId(Long fileId);

    /**
     * 根据空间ID和分类查找节点
     */
    List<Neo4jKnowledgeNode> findBySpaceIdAndCategory(Long spaceId, String category);

    /**
     * 根据名称模糊查询
     */
    List<Neo4jKnowledgeNode> findByNameContaining(String name);

    /**
     * 根据MySQL ID查找节点
     */
    Optional<Neo4jKnowledgeNode> findByMysqlId(Long mysqlId);

    /**
     * 查找节点的前置知识（多跳查询）
     */
    @Query("MATCH (n:KnowledgeNode {id: $nodeId})-[:PREREQUISITE_OF*1..3]->(prereq) " +
           "WHERE prereq.space_id = $spaceId " +
           "RETURN DISTINCT prereq")
    List<Neo4jKnowledgeNode> findPrerequisites(@Param("nodeId") String nodeId,
                                               @Param("spaceId") Long spaceId);

    /**
     * 查找节点的相关知识（一跳查询）
     */
    @Query("MATCH (n:KnowledgeNode {id: $nodeId})-[:RELATED_TO]-(related) " +
           "WHERE related.space_id = $spaceId " +
           "RETURN DISTINCT related")
    List<Neo4jKnowledgeNode> findRelatedNodes(@Param("nodeId") String nodeId,
                                              @Param("spaceId") Long spaceId);

    /**
     * 查找子图（包含节点及其N跳内的所有关联节点）
     */
    @Query("MATCH (n:KnowledgeNode {id: $nodeId})-[r*1..2]-(connected) " +
           "WHERE n.space_id = $spaceId " +
           "WITH n, connected, r " +
           "RETURN n, connected, r " +
           "LIMIT 50")
    List<Neo4jKnowledgeNode> findSubgraph(@Param("nodeId") String nodeId,
                                          @Param("spaceId") Long spaceId);

    /**
     * 根据空间ID和重要程度查找节点
     */
    List<Neo4jKnowledgeNode> findBySpaceIdAndImportanceGreaterThanEqual(Long spaceId, Integer importance);

    /**
     * 查找两个节点之间的最短路径
     */
    @Query("MATCH path = shortestPath((start:KnowledgeNode {id: $startNodeId})-[*..5]-(end:KnowledgeNode {id: $endNodeId})) " +
           "WHERE start.space_id = $spaceId AND end.space_id = $spaceId " +
           "RETURN path")
    List<Neo4jKnowledgeNode> findShortestPath(@Param("startNodeId") String startNodeId,
                                              @Param("endNodeId") String endNodeId,
                                              @Param("spaceId") Long spaceId);

    /**
     * 根据名称和知识库ID查找节点
     */
    Optional<Neo4jKnowledgeNode> findByNameAndKnowledgeBaseId(String name, Long knowledgeBaseId);

    /**
     * 根据名称和知识库ID查找节点（模糊匹配）
     */
    @Query("MATCH (n:KnowledgeNode) " +
           "WHERE n.name = $name AND n.knowledge_base_id = $knowledgeBaseId " +
           "RETURN n LIMIT 1")
    Optional<Neo4jKnowledgeNode> findByNameAndKnowledgeBaseIdExact(@Param("name") String name,
                                                                    @Param("knowledgeBaseId") Long knowledgeBaseId);

    /**
     * 根据文件ID查找节点之间的关系
     */
    @Query("MATCH (n:KnowledgeNode {file_id: $fileId})-[r]-(m:KnowledgeNode {file_id: $fileId}) " +
           "WHERE id(n) < id(m) " +
           "RETURN n.id AS sourceId, m.id AS targetId, type(r) AS relationType, " +
           "COALESCE(r.weight, 0.5) AS weight, COALESCE(r.description, '') AS description")
    List<RelationshipResult> findRelationshipsByFileId(@Param("fileId") Long fileId);

    /**
     * 根据知识库ID删除所有节点及其关系
     */
    @Query("MATCH (n:KnowledgeNode {knowledge_base_id: $knowledgeBaseId}) " +
           "DETACH DELETE n")
    void deleteByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);
}
