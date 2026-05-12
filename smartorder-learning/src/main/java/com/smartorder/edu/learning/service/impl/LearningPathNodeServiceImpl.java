package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.mapper.LearningPathNodeMapper;
import com.smartorder.edu.learning.service.ILearningPathNodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPathNodeServiceImpl implements ILearningPathNodeService {

    private final LearningPathNodeMapper nodeMapper;

    @Override
    public List<LearningPathNode> batchInsert(List<LearningPathNode> nodes) {
        for (LearningPathNode node : nodes) {
            if (node.getIsDeleted() == null) node.setIsDeleted(0);
            if (node.getDifficultyLevel() == null) node.setDifficultyLevel(1);
            if (node.getIsMilestone() == null) node.setIsMilestone(0);
            nodeMapper.insert(node);
        }
        return nodes;
    }

    @Override
    public List<LearningPathNode> getBySpaceId(Long spaceId) {
        QueryWrapper<LearningPathNode> qw = new QueryWrapper<>();
        qw.eq("space_id", spaceId).eq("is_deleted", 0).orderByAsc("sort_order");
        return nodeMapper.selectList(qw);
    }
}
