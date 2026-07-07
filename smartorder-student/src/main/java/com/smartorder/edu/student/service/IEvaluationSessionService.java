package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.vo.SessionResultVO;
import com.smartorder.edu.student.domain.vo.SessionReportVO;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 测评任务（Session）服务接口
 *
 * @author 马强
 * @since 2026-07-07
 */
public interface IEvaluationSessionService {

    /** 创建测评任务 */
    EvaluationSession createSession(Long learningSpaceId, Long userId, String title,
                                    List<Long> questionIds, BigDecimal totalScore,
                                    Map<String, Object> difficultyDistribution);

    /** 开始答题 */
    void startSession(Long sessionId, Long userId);

    /** 提交答案 */
    SessionResultVO submitAnswers(Long sessionId, Long userId, SubmitAnswersDTO dto);

    /** 查询判分结果 */
    SessionResultVO getSessionResult(Long sessionId, Long userId);

    /** 查询学习报告 */
    SessionReportVO getSessionReport(Long sessionId, Long userId);
}
