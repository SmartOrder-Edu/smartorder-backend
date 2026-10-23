package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.vo.EvaluationQuestionVO;
import com.smartorder.edu.student.domain.entity.EvaluationSession;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.List;

public interface IEvaluationQuestionService {

    /**
     * 根据课程、知识点和题型生成测评题目，并保存到题库。
     */
    void generateQuestions(GenerateQuestionDTO dto, SseEmitter emitter);

    /**
     * 同步生成测评题目并创建测评会话，供 Agent 工具和后台任务调用。
     */
    PracticeSetGenerationResult generatePracticeSet(GenerateQuestionDTO dto, Long userId);

    /**
     * 查询已保存的测评题目，供前端联调渲染题库列表。
     * <p>支持按学习空间ID过滤和数量限制，也支持按任务ID精确查询。</p>
     */
    List<EvaluationQuestionVO> listQuestions(Long learningSpaceId, Integer limit, Long taskId);

    /**
     * 查询测评任务（Session）列表，用于前端展示可参与的测评。
     * <p>返回完整 EvaluationSession，前端根据 status 判断是否已完成。</p>
     */
    List<EvaluationSession> listQuestionsTaskList(Long learningSpaceId, Long currentUserId);

    @Data
    @Builder
    class PracticeSetGenerationResult {
        private Long generationTaskId;
        private Long sessionId;
        private Long learningSpaceId;
        private String topic;
        private Integer questionCount;
        private BigDecimal totalScore;
        private String status;
    }
}
