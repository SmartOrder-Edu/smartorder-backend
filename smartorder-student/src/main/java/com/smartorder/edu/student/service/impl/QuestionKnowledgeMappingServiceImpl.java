package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.mapper.KnowledgeGraphNodeMapper;
import com.smartorder.edu.student.domain.dto.QuestionKnowledgeMappingSaveDTO;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionKnowledgeNode;
import com.smartorder.edu.student.domain.vo.QuestionKnowledgeMappingVO;
import com.smartorder.edu.student.mapper.EvaluationQuestionKnowledgeNodeMapper;
import com.smartorder.edu.student.mapper.EvaluationQuestionMapper;
import com.smartorder.edu.student.service.IQuestionKnowledgeMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuestionKnowledgeMappingServiceImpl implements IQuestionKnowledgeMappingService {

    private final EvaluationQuestionMapper questionMapper;
    private final EvaluationQuestionKnowledgeNodeMapper mappingMapper;
    private final KnowledgeGraphNodeMapper knowledgeGraphNodeMapper;

    @Override
    public QuestionKnowledgeMappingVO getMappings(Long questionId) {
        EvaluationQuestion question = requireQuestion(questionId);
        List<KnowledgeGraphNode> candidates = loadCandidateNodes(question.getLearningSpaceId());
        List<EvaluationQuestionKnowledgeNode> mappings = loadMappings(questionId);
        return QuestionKnowledgeMappingVO.of(question, mappings, candidates);
    }

    @Override
    @Transactional
    public QuestionKnowledgeMappingVO saveMappings(Long questionId, QuestionKnowledgeMappingSaveDTO dto) {
        EvaluationQuestion question = requireQuestion(questionId);
        if (dto == null || dto.getMappings() == null || dto.getMappings().isEmpty()) {
            throw new BusinessException("知识点映射不能为空");
        }

        List<KnowledgeGraphNode> candidates = loadCandidateNodes(question.getLearningSpaceId());
        Map<Long, KnowledgeGraphNode> candidateById = candidates.stream()
                .collect(Collectors.toMap(KnowledgeGraphNode::getId, Function.identity(), (a, b) -> a));

        Set<Long> seen = new HashSet<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (QuestionKnowledgeMappingSaveDTO.Item item : dto.getMappings()) {
            if (item.getKnowledgeNodeId() == null) {
                throw new BusinessException("知识点ID不能为空");
            }
            if (!candidateById.containsKey(item.getKnowledgeNodeId())) {
                throw new BusinessException("知识点不属于该题目的学习空间: %s", item.getKnowledgeNodeId());
            }
            if (!seen.add(item.getKnowledgeNodeId())) {
                throw new BusinessException("同一知识点不能重复映射: %s", item.getKnowledgeNodeId());
            }
            if (item.getWeight() == null || item.getWeight().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("映射权重必须大于0");
            }
            totalWeight = totalWeight.add(item.getWeight());
        }
        if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("映射权重总和必须大于0");
        }

        // Physical delete is intentional here. The table has a unique key containing is_deleted;
        // repeated logical deletes for the same pair can otherwise collide.
        mappingMapper.delete(new LambdaQueryWrapper<EvaluationQuestionKnowledgeNode>()
                .eq(EvaluationQuestionKnowledgeNode::getQuestionId, questionId));

        for (QuestionKnowledgeMappingSaveDTO.Item item : dto.getMappings()) {
            EvaluationQuestionKnowledgeNode mapping = new EvaluationQuestionKnowledgeNode();
            mapping.setQuestionId(questionId);
            mapping.setKnowledgeNodeId(item.getKnowledgeNodeId());
            mapping.setWeight(item.getWeight().divide(totalWeight, 4, RoundingMode.HALF_UP));
            mapping.setSource("manual");
            mapping.setConfidence(item.getConfidence() != null
                    ? item.getConfidence().setScale(4, RoundingMode.HALF_UP)
                    : BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP));
            mapping.setReason(item.getReason());
            mapping.setIsDeleted(0);
            mappingMapper.insert(mapping);
        }

        return getMappings(questionId);
    }

    private EvaluationQuestion requireQuestion(Long questionId) {
        if (questionId == null) {
            throw new BusinessException("题目ID不能为空");
        }
        EvaluationQuestion question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BusinessException("题目不存在: %s", questionId);
        }
        return question;
    }

    private List<EvaluationQuestionKnowledgeNode> loadMappings(Long questionId) {
        return mappingMapper.selectList(new LambdaQueryWrapper<EvaluationQuestionKnowledgeNode>()
                .eq(EvaluationQuestionKnowledgeNode::getQuestionId, questionId)
                .eq(EvaluationQuestionKnowledgeNode::getIsDeleted, 0)
                .orderByDesc(EvaluationQuestionKnowledgeNode::getWeight)
                .orderByAsc(EvaluationQuestionKnowledgeNode::getId));
    }

    private List<KnowledgeGraphNode> loadCandidateNodes(Long spaceId) {
        return knowledgeGraphNodeMapper.selectList(new LambdaQueryWrapper<KnowledgeGraphNode>()
                .eq(KnowledgeGraphNode::getSpaceId, spaceId)
                .eq(KnowledgeGraphNode::getIsDeleted, 0)
                .orderByDesc(KnowledgeGraphNode::getImportance)
                .orderByAsc(KnowledgeGraphNode::getId));
    }
}
