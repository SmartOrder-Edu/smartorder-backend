package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.LearningPathNode;

import java.util.List;

/**
 * 学习路径节点服务接口
 */
public interface ILearningPathNodeService {

    /**
     * 批量插入学习路径节点
     * @param nodes 节点列表
     * @return 插入后的节点列表（含自增ID）
     */
    List<LearningPathNode> batchInsert(List<LearningPathNode> nodes);

    /**
     * 根据学习空间ID查询节点列表
     * @param spaceId 空间ID
     * @return 节点列表（按sortOrder排序）
     */
    List<LearningPathNode> getBySpaceId(Long spaceId);
}
