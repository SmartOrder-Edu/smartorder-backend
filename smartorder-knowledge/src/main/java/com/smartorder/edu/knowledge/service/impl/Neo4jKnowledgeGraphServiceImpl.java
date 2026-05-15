package com.smartorder.edu.knowledge.service.impl;

import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.domain.entity.Neo4jKnowledgeNode;
import com.smartorder.edu.knowledge.mapper.KnowledgeGraphEdgeMapper;
import com.smartorder.edu.knowledge.mapper.KnowledgeGraphNodeMapper;
import com.smartorder.edu.knowledge.repository.Neo4jKnowledgeNodeRepository;
import com.smartorder.edu.knowledge.service.INeo4jKnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Neo4j 知识图谱服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Neo4jKnowledgeGraphServiceImpl implements INeo4jKnowledgeGraphService {

    private final Neo4jKnowledgeNodeRepository neo4jRepository;
    private final KnowledgeGraphNodeMapper mysqlNodeMapper;
    private final KnowledgeGraphEdgeMapper mysqlEdgeMapper;

    @Override
    @Transactional
    public Neo4jKnowledgeNode createNode(Neo4jKnowledgeNode node) {
        if (node.getId() == null) {
            node.setId(UUID.randomUUID().toString());
        }
        return neo4jRepository.save(node);
    }

    @Override
    @Transactional
    public List<Neo4jKnowledgeNode> batchCreateNodes(List<Neo4jKnowledgeNode> nodes) {
        nodes.forEach(node -> {
            if (node.getId() == null) {
                node.setId(UUID.randomUUID().toString());
            }
        });
        return new ArrayList<>(neo4jRepository.saveAll(nodes));
    }

    @Override
    @Transactional
    public void createRelation(String sourceNodeId, String targetNodeId, String relationType, 
                               Double weight, Long spaceId) {
        Neo4jKnowledgeNode sourceNode = neo4jRepository.findById(sourceNodeId)
                .orElseThrow(() -> new RuntimeException("源节点不存在: " + sourceNodeId));
        Neo4jKnowledgeNode targetNode = neo4jRepository.findById(targetNodeId)
                .orElseThrow(() -> new RuntimeException("目标节点不存在: " + targetNodeId));

        switch (relationType.toUpperCase()) {
            case "PREREQUISITE_OF":
                sourceNode.getPrerequisites().add(targetNode);
                break;
            case "RELATED_TO":
                sourceNode.getRelatedNodes().add(targetNode);
                break;
            case "PART_OF":
                sourceNode.getParentNodes().add(targetNode);
                break;
            default:
                sourceNode.getRelatedNodes().add(targetNode);
                break;
        }

        neo4jRepository.save(sourceNode);
    }

    @Override
    public List<Neo4jKnowledgeNode> findNodesBySpaceId(Long spaceId) {
        return neo4jRepository.findBySpaceId(spaceId);
    }

    @Override
    public List<Neo4jKnowledgeNode> findPrerequisites(String nodeId, Long spaceId) {
        return neo4jRepository.findPrerequisites(nodeId, spaceId);
    }

    @Override
    public List<Neo4jKnowledgeNode> findRelatedNodes(String nodeId, Long spaceId) {
        return neo4jRepository.findRelatedNodes(nodeId, spaceId);
    }

    @Override
    public List<Neo4jKnowledgeNode> findSubgraph(String nodeId, Long spaceId) {
        return neo4jRepository.findSubgraph(nodeId, spaceId);
    }

    @Override
    public List<Neo4jKnowledgeNode> findShortestPath(String startNodeId, String endNodeId, Long spaceId) {
        return neo4jRepository.findShortestPath(startNodeId, endNodeId, spaceId);
    }

    @Override
    public List<Neo4jKnowledgeNode> searchNodesByName(String name) {
        return neo4jRepository.findByNameContaining(name);
    }

    @Override
    public Neo4jKnowledgeNode findByMysqlId(Long mysqlId) {
        return neo4jRepository.findByMysqlId(mysqlId).orElse(null);
    }

    @Override
    @Transactional
    public void syncFromMySQL(Long spaceId) {
        log.info("开始同步MySQL知识图谱到Neo4j，空间ID: {}", spaceId);

        // 1. 同步节点
        List<KnowledgeGraphNode> mysqlNodes = mysqlNodeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeGraphNode>()
                        .eq("space_id", spaceId)
                        .eq("is_deleted", 0));

        List<Neo4jKnowledgeNode> neo4jNodes = mysqlNodes.stream()
                .map(this::convertToNeo4jNode)
                .collect(Collectors.toList());

        neo4jRepository.saveAll(neo4jNodes);
        log.info("同步节点完成，数量: {}", neo4jNodes.size());

        // 2. 同步关系
        List<KnowledgeGraphEdge> mysqlEdges = mysqlEdgeMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KnowledgeGraphEdge>()
                        .eq("space_id", spaceId)
                        .eq("is_deleted", 0));

        for (KnowledgeGraphEdge edge : mysqlEdges) {
            try {
                Neo4jKnowledgeNode sourceNode = findByMysqlId(edge.getFromNodeId());
                Neo4jKnowledgeNode targetNode = findByMysqlId(edge.getToNodeId());

                if (sourceNode != null && targetNode != null) {
                    createRelation(sourceNode.getId(), targetNode.getId(), 
                                  edge.getRelationType(), edge.getWeight(), spaceId);
                }
            } catch (Exception e) {
                log.warn("同步关系失败，边ID: {}, 错误: {}", edge.getId(), e.getMessage());
            }
        }

        log.info("同步关系完成，数量: {}", mysqlEdges.size());
    }

    private Neo4jKnowledgeNode convertToNeo4jNode(KnowledgeGraphNode mysqlNode) {
        Neo4jKnowledgeNode neo4jNode = new Neo4jKnowledgeNode();
        neo4jNode.setId(UUID.randomUUID().toString());
        neo4jNode.setMysqlId(mysqlNode.getId());
        neo4jNode.setSpaceId(mysqlNode.getSpaceId());
        neo4jNode.setName(mysqlNode.getName());
        neo4jNode.setDescription(mysqlNode.getDescription());
        neo4jNode.setCategory(mysqlNode.getCategory());
        neo4jNode.setImportance(mysqlNode.getImportance());
        return neo4jNode;
    }
}