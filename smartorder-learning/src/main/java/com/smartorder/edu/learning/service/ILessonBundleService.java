package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.vo.LessonBundleStatusVO;
import com.smartorder.edu.learning.dto.LessonBundleGenerateDTO;

public interface ILessonBundleService {
    LessonBundleStatusVO generateBundle(Long userId, LessonBundleGenerateDTO dto);

    LessonBundleStatusVO getBundleStatus(Long userId, String taskId, Long pathNodeId, String spaceId, String mode);

    void preheatNextPathNode(Long userId, Long currentPathNodeId, String mode);
}
