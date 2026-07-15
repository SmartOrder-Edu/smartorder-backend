package com.smartorder.edu.student.domain.vo;

import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.student.domain.entity.EvaluationQuestion;
import com.smartorder.edu.student.domain.entity.EvaluationQuestionKnowledgeNode;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
public class QuestionKnowledgeMappingVO {

    private Long questionId;
    private Long learningSpaceId;
    private String questionType;
    private Integer difficulty;
    private String stem;
    private List<String> questionTags;
    private List<String> questionKnowledgePoints;
    private List<MappingItem> mappings;
    private List<CandidateNode> candidates;

    public static QuestionKnowledgeMappingVO of(EvaluationQuestion question,
                                                List<EvaluationQuestionKnowledgeNode> mappings,
                                                List<KnowledgeGraphNode> candidates) {
        QuestionKnowledgeMappingVO vo = new QuestionKnowledgeMappingVO();
        vo.setQuestionId(question.getId());
        vo.setLearningSpaceId(question.getLearningSpaceId());
        vo.setQuestionType(question.getQuestionType());
        vo.setDifficulty(question.getDifficulty());
        vo.setStem(question.getStem());
        vo.setQuestionTags(question.getTags());
        vo.setQuestionKnowledgePoints(question.getKnowledgePoints());

        Map<Long, KnowledgeGraphNode> nodeById = candidates.stream()
                .collect(Collectors.toMap(KnowledgeGraphNode::getId, Function.identity(), (a, b) -> a));

        vo.setMappings(mappings.stream()
                .map(mapping -> MappingItem.of(mapping, nodeById.get(mapping.getKnowledgeNodeId())))
                .toList());
        vo.setCandidates(candidates.stream()
                .map(node -> CandidateNode.of(node, mappings.stream()
                        .anyMatch(mapping -> mapping.getKnowledgeNodeId().equals(node.getId()))))
                .toList());
        return vo;
    }

    @Data
    public static class MappingItem {
        private Long id;
        private Long knowledgeNodeId;
        private String knowledgeNodeName;
        private String category;
        private Integer importance;
        private BigDecimal weight;
        private String source;
        private BigDecimal confidence;
        private String reason;

        static MappingItem of(EvaluationQuestionKnowledgeNode mapping, KnowledgeGraphNode node) {
            MappingItem item = new MappingItem();
            item.setId(mapping.getId());
            item.setKnowledgeNodeId(mapping.getKnowledgeNodeId());
            item.setKnowledgeNodeName(node != null ? node.getName() : null);
            item.setCategory(node != null ? node.getCategory() : null);
            item.setImportance(node != null ? node.getImportance() : null);
            item.setWeight(mapping.getWeight());
            item.setSource(mapping.getSource());
            item.setConfidence(mapping.getConfidence());
            item.setReason(mapping.getReason());
            return item;
        }
    }

    @Data
    public static class CandidateNode {
        private Long id;
        private String name;
        private String description;
        private String category;
        private Integer importance;
        private Boolean mapped;

        static CandidateNode of(KnowledgeGraphNode node, boolean mapped) {
            CandidateNode item = new CandidateNode();
            item.setId(node.getId());
            item.setName(node.getName());
            item.setDescription(node.getDescription());
            item.setCategory(node.getCategory());
            item.setImportance(node.getImportance());
            item.setMapped(mapped);
            return item;
        }
    }
}
