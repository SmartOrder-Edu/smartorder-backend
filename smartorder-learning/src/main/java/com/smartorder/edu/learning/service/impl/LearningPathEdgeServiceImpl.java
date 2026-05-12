package com.smartorder.edu.learning.service.impl;

import com.smartorder.edu.learning.domain.entity.LearningPathEdge;
import com.smartorder.edu.learning.mapper.LearningPathEdgeMapper;
import com.smartorder.edu.learning.service.ILearningPathEdgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPathEdgeServiceImpl implements ILearningPathEdgeService {

    private final LearningPathEdgeMapper edgeMapper;

    @Override
    public void batchInsert(List<LearningPathEdge> edges) {
        for (LearningPathEdge edge : edges) {
            if (edge.getIsDeleted() == null) edge.setIsDeleted(0);
            edgeMapper.insert(edge);
        }
    }
}
