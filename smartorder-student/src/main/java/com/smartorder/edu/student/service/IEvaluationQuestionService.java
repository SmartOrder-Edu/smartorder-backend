package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.vo.GenerateQuestionVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface IEvaluationQuestionService {

    /**
     * 根据课程、知识点和题型生成测评题目，并保存到题库。
     */
    void generateQuestions(GenerateQuestionDTO dto, SseEmitter emitter);

    /**
     * 查询已保存的测评题目，供前端联调渲染题库列表。
     */
    List<GenerateQuestionVO.QuestionVO> listQuestions(Long learningSpaceId, Integer limit);
}
