package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.entity.ResourceContent;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;

public record ResourceGenerationResult(
        Resource resource,
        ResourceContent content,
        LearningSpaceVO space,
        Task task
) {
}
