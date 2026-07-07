package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyEventReceiptVO;
import com.smartorder.edu.learning.domain.vo.StudySessionCompleteVO;
import com.smartorder.edu.learning.dto.StudyEventDTO;
import com.smartorder.edu.learning.dto.StudySessionCompleteDTO;
import com.smartorder.edu.learning.dto.StudySessionStartDTO;
import com.smartorder.edu.learning.dto.StudyTeachingActionStatusDTO;

public interface IStudySessionService {
    StudySessionVO startSession(Long userId, StudySessionStartDTO dto);

    StudySessionVO getSession(Long userId, String sessionId);

    StudyEventReceiptVO recordEvent(Long userId, String sessionId, StudyEventDTO dto);

    StudySessionCompleteVO completeSession(Long userId, String sessionId, StudySessionCompleteDTO dto);

    void updateTeachingActionStatus(Long userId, String sessionId, String actionId, StudyTeachingActionStatusDTO dto);
}
