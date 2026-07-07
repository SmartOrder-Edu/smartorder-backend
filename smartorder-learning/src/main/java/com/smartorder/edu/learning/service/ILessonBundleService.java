package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.vo.LessonBundleStatusVO;
import com.smartorder.edu.learning.dto.LessonBundleGenerateDTO;
import com.smartorder.edu.learning.dto.LessonBundleStatusBatchDTO;

import java.util.List;

public interface ILessonBundleService {
    LessonBundleStatusVO generateBundle(Long userId, LessonBundleGenerateDTO dto);

    LessonBundleStatusVO getBundleStatus(Long userId, String taskId, Long pathNodeId, String spaceId, String mode);

    List<LessonBundleStatusVO> getBundleStatuses(Long userId, LessonBundleStatusBatchDTO dto);

    void preheatNextPathNode(Long userId, Long currentPathNodeId, String mode);
}
