package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

public interface IEvaluationQuestionService {

    /**
     * 根据课程、知识点和题型生成测评题目，并保存到题库。
     */
    void generateQuestions(GenerateQuestionDTO dto, SseEmitter emitter);
}
