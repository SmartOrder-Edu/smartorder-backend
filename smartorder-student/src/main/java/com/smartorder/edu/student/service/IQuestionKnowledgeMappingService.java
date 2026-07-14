package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.QuestionKnowledgeMappingSaveDTO;
import com.smartorder.edu.student.domain.vo.QuestionKnowledgeMappingVO;

public interface IQuestionKnowledgeMappingService {

    QuestionKnowledgeMappingVO getMappings(Long questionId);

    QuestionKnowledgeMappingVO saveMappings(Long questionId, QuestionKnowledgeMappingSaveDTO dto);
}
