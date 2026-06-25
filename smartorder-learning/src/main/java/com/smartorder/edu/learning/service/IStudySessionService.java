package com.smartorder.edu.learning.service;

import com.smartorder.edu.learning.domain.vo.StudySessionVO;
import com.smartorder.edu.learning.domain.vo.StudyEventReceiptVO;
import com.smartorder.edu.learning.dto.StudyEventDTO;
import com.smartorder.edu.learning.dto.StudySessionStartDTO;

public interface IStudySessionService {
    StudySessionVO startSession(Long userId, StudySessionStartDTO dto);

    StudySessionVO getSession(Long userId, String sessionId);

    StudyEventReceiptVO recordEvent(Long userId, String sessionId, StudyEventDTO dto);
}
