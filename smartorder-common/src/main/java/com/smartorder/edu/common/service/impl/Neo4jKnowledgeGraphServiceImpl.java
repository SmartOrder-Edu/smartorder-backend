package com.smartorder.edu.common.service.impl;

import com.smartorder.edu.common.mapper.Neo4jKnowledgeNodeRepository;
import com.smartorder.edu.common.model.dto.RelationshipResult;
import com.smartorder.edu.common.model.entity.Neo4jKnowledgeNode;
import com.smartorder.edu.common.model.entity.Neo4jKnowledgeRelation;
import com.smartorder.edu.common.model.vo.KnowledgeGraphVO;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Neo4j 知识图谱服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Neo4jKnowledgeGraphServiceImpl implements INeo4jKnowledgeGraphService {

    private final Neo4jKnowledgeNodeRepository neo4jRepository;

    /**
     * 应用启动时初始化所有关系类型
     * 目的：确保数据库中存在所有关系类型，避免 Spring Data Neo4j 查询时的警告
     */
    @PostConstruct
    public void initRelationshipTypes() {
        log.info("开始初始化 Neo4j 关系类型...");

        try {
            // 定义所有关系类型
            String[] relationTypes = {
                "PREREQUISITE_OF", "RELATED_TO", "PART_OF", "CAUSE_EFFECT",
                "COMPARE_WITH", "STEP_OF", "RECOMMEND", "DEPENDS_ON"
            };

            // 检查是否已存在测试节点
            Neo4jKnowledgeNode existingNode = neo4jRepository.findById("test_init_node_1").orElse(null);
            if (existingNode != null) {
                log.info("关系类型初始化已完成，跳过...");
                return;
            }

            // 创建测试节点
            Neo4jKnowledgeNode node1 = new Neo4jKnowledgeNode();
            node1.setId("test_init_node_1");
            node1.setName("测试初始化节点1");
            node1.setDescription("用于初始化关系类型的测试节点");
            node1.setSpaceId(0L);
            node1.setKnowledgeBaseId(0L);
            node1.setFileId(0L);
            neo4jRepository.save(node1);

            Neo4jKnowledgeNode node2 = new Neo4jKnowledgeNode();
            node2.setId("test_init_node_2");
            node2.setName("测试初始化节点2");
            node2.setDescription("用于初始化关系类型的测试节点");
            node2.setSpaceId(0L);
            node2.setKnowledgeBaseId(0L);
            node2.setFileId(0L);
            neo4jRepository.save(node2);

            // 为每种关系类型创建测试关系
            for (String relationType : relationTypes) {
                try {
                    createRelation("test_init_node_1", "test_init_node_2", relationType, 0.5, 0L, "测试" + relationType + "关系");
                    log.info("创建关系类型: {}", relationType);
                } catch (Exception e) {
                    log.warn("创建关系类型 {} 失败: {}", relationType, e.getMessage());
                }
            }

            log.info("Neo4j 关系类型初始化完成");

        } catch (Exception e) {
            log.error("初始化 Neo4j 关系类型失败", e);
        }
    }

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
                               Double weight, Long spaceId, String description) {
        Neo4jKnowledgeNode sourceNode = neo4jRepository.findById(sourceNodeId)
                .orElseThrow(() -> new RuntimeException("源节点不存在: " + sourceNodeId));
        Neo4jKnowledgeNode targetNode = neo4jRepository.findById(targetNodeId)
                .orElseThrow(() -> new RuntimeException("目标节点不存在: " + targetNodeId));

        Neo4jKnowledgeRelation relation = new Neo4jKnowledgeRelation(
                UUID.randomUUID().toString(), targetNode, relationType, weight, spaceId, description);

        switch (relationType.toUpperCase()) {
            case "PREREQUISITE_OF":
                sourceNode.getPrerequisites().add(relation);
                break;
            case "RELATED_TO":
                sourceNode.getRelatedNodes().add(relation);
                break;
            case "PART_OF":
                sourceNode.getParentNodes().add(relation);
                break;
            case "CAUSE_EFFECT":
                sourceNode.getCauseEffectNodes().add(relation);
                break;
            case "COMPARE_WITH":
                sourceNode.getCompareNodes().add(relation);
                break;
            case "STEP_OF":
                sourceNode.getStepOfNodes().add(relation);
                break;
            case "RECOMMEND":
                sourceNode.getRecommendNodes().add(relation);
                break;
            case "DEPENDS_ON":
                sourceNode.getDependsOnNodes().add(relation);
                break;
            default:
                sourceNode.getRelatedNodes().add(relation);
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
    @Transactional(readOnly = true)
    public Neo4jKnowledgeNode findByMysqlId(Long mysqlId) {
        return neo4jRepository.findByMysqlId(mysqlId).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
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
    public void deleteNodesByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            neo4jRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
            log.info("已删除知识库相关的图节点，knowledgeBaseId={}", knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除知识库图节点失败，knowledgeBaseId={}", knowledgeBaseId, e);
            throw e;
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
        List<RelationshipResult> relationships = neo4jRepository.findRelationshipsByFileId(fileId);
        List<KnowledgeGraphVO.EdgeVO> edgeVOs = new ArrayList<>();
        for (RelationshipResult rel : relationships) {
            KnowledgeGraphVO.EdgeVO edgeVO = new KnowledgeGraphVO.EdgeVO();
            edgeVO.setSource(rel.getSourceId());
            edgeVO.setTarget(rel.getTargetId());
            edgeVO.setRelationType(rel.getRelationType());
            edgeVO.setWeight(rel.getWeight());
            edgeVO.setDescription(rel.getDescription());
            edgeVOs.add(edgeVO);
        }
        vo.setEdges(edgeVOs);

        return vo;
    }
}
