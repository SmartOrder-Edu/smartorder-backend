package com.smartorder.edu.knowledge.service.impl;

import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;
import com.smartorder.edu.knowledge.mapper.KnowledgeGraphNodeMapper;
import com.smartorder.edu.knowledge.mapper.KnowledgeGraphEdgeMapper;
import com.smartorder.edu.knowledge.service.IKnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphServiceImpl implements IKnowledgeGraphService {

    private final KnowledgeGraphNodeMapper nodeMapper;
    private final KnowledgeGraphEdgeMapper edgeMapper;

    @Override
    public List<KnowledgeGraphNode> batchInsertNodes(List<KnowledgeGraphNode> nodes) {
        for (KnowledgeGraphNode node : nodes) {
            if (node.getIsDeleted() == null) node.setIsDeleted(0);
            if (node.getImportance() == null) node.setImportance(3);
            nodeMapper.insert(node);
        }
        return nodes;
    }

    @Override
    public void batchInsertEdges(List<KnowledgeGraphEdge> edges) {
        for (KnowledgeGraphEdge edge : edges) {
            if (edge.getIsDeleted() == null) edge.setIsDeleted(0);
            if (edge.getWeight() == null) edge.setWeight(1.0);
            edgeMapper.insert(edge);
        }
    }
}
