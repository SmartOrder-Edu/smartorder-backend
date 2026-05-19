package com.smartorder.edu.knowledge.service;

import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;

import java.util.List;

/**
 * 知识图谱服务接口
 */
public interface IKnowledgeGraphService {

    /**
     * 批量插入知识图谱节点
     */
    List<KnowledgeGraphNode> batchInsertNodes(List<KnowledgeGraphNode> nodes);

    /**
     * 批量插入知识图谱边
     */
    void batchInsertEdges(List<KnowledgeGraphEdge> edges);

    /**
     * 根据学习空间ID查询知识图谱节点
     */
    List<KnowledgeGraphNode> getNodesBySpaceId(Long spaceId);

    /**
     * 根据学习空间ID查询知识图谱边
     */
    List<KnowledgeGraphEdge> getEdgesBySpaceId(Long spaceId);

    /**
     * 根据关键字搜索知识图谱节点
     */
    List<KnowledgeGraphNode> searchNodes(Long spaceId, String keyword);
}
