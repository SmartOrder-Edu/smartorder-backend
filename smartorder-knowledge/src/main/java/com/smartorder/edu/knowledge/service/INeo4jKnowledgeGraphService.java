package com.smartorder.edu.knowledge.service;

import com.smartorder.edu.knowledge.domain.entity.Neo4jKnowledgeNode;

import java.util.List;

/**
 * Neo4j 知识图谱服务接口
 */
public interface INeo4jKnowledgeGraphService {

    /**
     * 创建知识节点
     */
    Neo4jKnowledgeNode createNode(Neo4jKnowledgeNode node);

    /**
     * 批量创建知识节点
     */
    List<Neo4jKnowledgeNode> batchCreateNodes(List<Neo4jKnowledgeNode> nodes);

    /**
     * 创建关系
     */
    void createRelation(String sourceNodeId, String targetNodeId, String relationType, Double weight, Long spaceId);

    /**
     * 根据空间ID查找所有节点
     */
    List<Neo4jKnowledgeNode> findNodesBySpaceId(Long spaceId);

    /**
     * 查找节点的前置知识（多跳推理）
     */
    List<Neo4jKnowledgeNode> findPrerequisites(String nodeId, Long spaceId);

    /**
     * 查找节点的相关知识
     */
    List<Neo4jKnowledgeNode> findRelatedNodes(String nodeId, Long spaceId);

    /**
     * 查找子图（用于图RAG检索）
     */
    List<Neo4jKnowledgeNode> findSubgraph(String nodeId, Long spaceId);

    /**
     * 查找两个节点之间的最短路径
     */
    List<Neo4jKnowledgeNode> findShortestPath(String startNodeId, String endNodeId, Long spaceId);

    /**
     * 根据名称搜索节点
     */
    List<Neo4jKnowledgeNode> searchNodesByName(String name);

    /**
     * 根据MySQL ID查找Neo4j节点
     */
    Neo4jKnowledgeNode findByMysqlId(Long mysqlId);

    /**
     * 同步MySQL知识图谱到Neo4j
     */
    void syncFromMySQL(Long spaceId);
}