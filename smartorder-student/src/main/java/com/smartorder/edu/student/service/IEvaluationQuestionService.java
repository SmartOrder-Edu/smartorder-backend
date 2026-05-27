package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;

import java.util.List;

public interface IEvaluationQuestionService {

    /**
     * 根据课程、知识点和题型生成测评题目，并保存到题库。
     */
    List<EvaluationQuestion> generateQuestions(GenerateQuestionDTO dto);
}
