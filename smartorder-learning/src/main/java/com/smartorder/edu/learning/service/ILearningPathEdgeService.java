package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.LearningPathEdge;

import java.util.List;

/**
 * 学习路径边服务接口
 */
public interface ILearningPathEdgeService {

    /**
     * 批量插入边
     */
    void batchInsert(List<LearningPathEdge> edges);
}
