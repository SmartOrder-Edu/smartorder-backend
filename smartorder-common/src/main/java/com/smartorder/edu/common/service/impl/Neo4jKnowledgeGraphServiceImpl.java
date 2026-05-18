package com.smartorder.edu.common.service.impl;

import com.smartorder.edu.common.mapper.Neo4jKnowledgeNodeRepository;
import com.smartorder.edu.common.model.entity.Neo4jKnowledgeNode;
import com.smartorder.edu.common.model.vo.KnowledgeGraphVO;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Neo4j 知识图谱服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Neo4jKnowledgeGraphServiceImpl implements INeo4jKnowledgeGraphService {

    private final Neo4jKnowledgeNodeRepository neo4jRepository;

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
    public List<Neo4jKnowledgeNode> findNodesByKnowledgeBaseId(Long knowledgeBaseId) {
        return neo4jRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }

    @Override
    public List<Neo4jKnowledgeNode> findNodesByFileId(Long fileId) {
        return neo4jRepository.findByFileId(fileId);
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
    public Neo4jKnowledgeNode findByNameAndKnowledgeBaseId(String name, Long knowledgeBaseId) {
        return neo4jRepository.findByNameAndKnowledgeBaseIdExact(name, knowledgeBaseId).orElse(null);
    }

    @Override
    @Transactional
    public void deleteNodesByFileId(Long fileId) {
        List<Neo4jKnowledgeNode> nodes = neo4jRepository.findByFileId(fileId);
        if (!nodes.isEmpty()) {
            neo4jRepository.deleteAll(nodes);
            log.info("已删除文件相关的图节点，fileId={}, count={}", fileId, nodes.size());
        }
    }

    @Override
    @Transactional
    public void syncFromMySQL(Long spaceId) {
        // 此方法保留为兼容，但具体实现需要依赖 MySQL 的 Mapper
        // 在 common 模块中暂不实现，由 knowledge 模块的 Controller 调用
        log.warn("syncFromMySQL 方法需要在具体业务模块中实现");
    }

    @Override
    public KnowledgeGraphVO getFileKnowledgeGraph(Long fileId) {
        KnowledgeGraphVO vo = new KnowledgeGraphVO();
        vo.setFileId(fileId);

        // 查询文件的所有节点
        List<Neo4jKnowledgeNode> neo4jNodes = neo4jRepository.findByFileId(fileId);

        // 转换节点
        List<KnowledgeGraphVO.NodeVO> nodeVOs = new ArrayList<>();
        for (Neo4jKnowledgeNode node : neo4jNodes) {
            KnowledgeGraphVO.NodeVO nodeVO = new KnowledgeGraphVO.NodeVO();
            nodeVO.setId(node.getId());
            nodeVO.setName(node.getName());
            nodeVO.setDescription(node.getDescription());
            nodeVO.setCategory(node.getCategory());
            nodeVO.setImportance(node.getImportance());
            nodeVOs.add(nodeVO);
        }
        vo.setNodes(nodeVOs);

        // 查询节点之间的关系
        List<Map<String, Object>> relationships = neo4jRepository.findRelationshipsByFileId(fileId);
        List<KnowledgeGraphVO.EdgeVO> edgeVOs = new ArrayList<>();
        for (Map<String, Object> rel : relationships) {
            KnowledgeGraphVO.EdgeVO edgeVO = new KnowledgeGraphVO.EdgeVO();
            edgeVO.setSource((String) rel.get("sourceId"));
            edgeVO.setTarget((String) rel.get("targetId"));
            edgeVO.setRelationType((String) rel.get("relationType"));
            edgeVOs.add(edgeVO);
        }
        vo.setEdges(edgeVOs);

        return vo;
    }
}
