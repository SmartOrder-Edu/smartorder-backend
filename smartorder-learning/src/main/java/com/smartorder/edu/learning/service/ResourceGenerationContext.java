package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;

import java.util.Map;

public record ResourceGenerationContext(
        Resource resource,
        LearningSpaceVO space,
        Task task,
        LearningPathNode pathNode,
        String knowledgePoint,
        String reason,
        String studyScenario,
        String userIntent,
        String packageMode,
        int targetMinutes,
        Map<String, Object> metadata
) {
}
