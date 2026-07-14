package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.entity.EvaluationSession;
import com.smartorder.edu.student.domain.dto.SubmitAnswersDTO;
import com.smartorder.edu.student.domain.vo.EvaluationQuestionVO;
import com.smartorder.edu.student.domain.vo.SessionResultVO;
import com.smartorder.edu.student.domain.vo.SessionReportVO;
import com.smartorder.edu.student.domain.vo.DraftAnswerVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 测评任务（Session）服务接口
 *
 * @author 马强
 * @since 2026-07-07
 */
public interface IEvaluationSessionService {

    /** 创建测评任务 */
    EvaluationSession createSession(Long learningSpaceId, Long userId, String topic,
                                    Long taskId, BigDecimal totalScore, int totalQuestionCount);

    /** 开始答题 */
    void startSession(Long sessionId, Long userId);

    /** 获取试卷题目列表 */
    List<EvaluationQuestionVO> getSessionQuestions(Long sessionId, Long userId);

    /** 提交答案（批量判分 + 更新 session 分数） */
    SessionResultVO submitAnswers(Long sessionId, Long userId, SubmitAnswersDTO dto);

    /** 保存答题进度（草稿，不判分，status=1） */
    void saveProgress(Long sessionId, Long userId, SubmitAnswersDTO dto);

    /** 获取草稿答案（用于恢复答题进度） */
    List<DraftAnswerVO> getDraftAnswers(Long sessionId, Long userId);

    /** 查询判分结果（含主观题轮询） */
    SessionResultVO getSessionResult(Long sessionId, Long userId);

    /** 查询学习报告 */
    SessionReportVO getSessionReport(Long sessionId, Long userId);
}
