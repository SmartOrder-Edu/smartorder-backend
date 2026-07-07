package com.smartorder.edu.student.service.impl;

import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.vo.SessionResultVO;
import com.smartorder.edu.student.domain.vo.SessionReportVO;
import com.smartorder.edu.student.mapper.EvaluationSessionMapper;
import com.smartorder.edu.student.service.IEvaluationSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 测评任务（Session）服务实现（最小化实现）
 *
 * @author 马强
 * @since 2026-07-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationSessionServiceImpl implements IEvaluationSessionService {

    private final EvaluationSessionMapper sessionMapper;

    @Override
    public EvaluationSession createSession(Long learningSpaceId, Long userId, String title,
                                            List<Long> questionIds, BigDecimal totalScore,
                                            Map<String, Object> difficultyDistribution) {
        EvaluationSession session = new EvaluationSession();
        session.setUserId(userId);
        session.setLearningSpaceId(learningSpaceId);
        session.setTitle(title);
        session.setSessionType("chapter");
        session.setQuestionIds(questionIds);
        session.setTotalQuestionCount(questionIds != null ? questionIds.size() : 0);
        session.setTotalScore(totalScore);
        session.setDifficultyDistribution(difficultyDistribution);
        session.setStatus("created");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        log.info("创建测评任务: sessionId={}, title={}", session.getId(), title);
        return session;
    }

    @Override
    public void startSession(Long sessionId, Long userId) {
        EvaluationSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("测评任务不存在或无权访问");
        }
        session.setStatus("doing");
        session.setStartedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Override
    public SessionResultVO submitAnswers(Long sessionId, Long userId, SubmitAnswersDTO dto) {
        throw new BusinessException("Session 模式暂未实现，请使用 /evaluation/submit 直接提交");
    }

    @Override
    public SessionResultVO getSessionResult(Long sessionId, Long userId) {
        throw new BusinessException("Session 模式暂未实现，请使用 /evaluation/result 查询");
    }

    @Override
    public SessionReportVO getSessionReport(Long sessionId, Long userId) {
        throw new BusinessException("Session 报告暂未实现");
    }
}
