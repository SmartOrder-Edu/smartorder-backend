package com.smartorder.edu.agent.rag.retriever;

import com.smartorder.edu.agent.rag.config.RagConfig;
import com.smartorder.edu.agent.rag.model.RagChunk;
import com.smartorder.edu.common.model.entity.Neo4jKnowledgeNode;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 图 RAG 核心服务
 * 负责图数据库的实体关系查询、多跳推理、子图提取
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphRagService {

    private final INeo4jKnowledgeGraphService neo4jService;
    private final RagConfig ragConfig;

    /**
     * 执行图 RAG 检索
     *
     * @param entities         实体列表
     * @param knowledgeBaseIds 知识库ID列表
     * @param query            查询文本
     * @param isComplex        是否为复杂查询
     * @return 检索到的文档块
     */
    public List<RagChunk> retrieve(List<String> entities, List<Long> knowledgeBaseIds,
                                    String query, boolean isComplex) {
        if (entities == null || entities.isEmpty()) {
            log.warn("[GraphRagService] 实体列表为空，跳过图检索");
            return Collections.emptyList();
        }

        log.info("[GraphRagService] 开始图检索，实体: {}, 知识库: {}, 复杂模式: {}",
                entities, knowledgeBaseIds, isComplex);

        try {
            if (isComplex) {
                return complexRetrieve(entities, knowledgeBaseIds, query);
            } else {
                return simpleRetrieve(entities, knowledgeBaseIds);
            }
        } catch (Exception e) {
            log.error("[GraphRagService] 图检索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 简单图检索：直接实体关系查询
     */
    private List<RagChunk> simpleRetrieve(List<String> entities, List<Long> knowledgeBaseIds) {
        List<RagChunk> chunks = new ArrayList<>();
        Set<String> processedNodeIds = new HashSet<>();

        for (String entity : entities) {
            for (Long kbId : knowledgeBaseIds) {
                try {
                    // 搜索节点
                    List<Neo4jKnowledgeNode> nodes = neo4jService.searchNodesByName(entity);

                    for (Neo4jKnowledgeNode node : nodes) {
                        if (processedNodeIds.contains(node.getId())) {
                            continue;
                        }
                        processedNodeIds.add(node.getId());

                        // 添加节点本身
                        chunks.add(RagChunk.fromGraphNode(
                                node.getId(),
                                node.getName(),
                                node.getDescription(),
                                node.getCategory(),
                                kbId
                        ));

                        // 查找相关节点（一度关系）
                        List<Neo4jKnowledgeNode> relatedNodes = neo4jService.findRelatedNodes(
                                node.getId(), kbId);
                        for (Neo4jKnowledgeNode related : relatedNodes) {
                            if (!processedNodeIds.contains(related.getId())) {
                                processedNodeIds.add(related.getId());
                                chunks.add(RagChunk.fromGraphNode(
                                        related.getId(),
                                        related.getName(),
                                        related.getDescription(),
                                        related.getCategory(),
                                        kbId
                                ));
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("[GraphRagService] 搜索实体失败: {}, kbId: {}", entity, kbId, e);
                }
            }
        }

        log.info("[GraphRagService] 简单图检索完成，结果数量: {}", chunks.size());
        return chunks;
    }

    /**
     * 复杂图检索：多跳推理 + 子图提取
     */
    private List<RagChunk> complexRetrieve(List<String> entities, List<Long> knowledgeBaseIds,
                                            String query) {
        List<RagChunk> chunks = new ArrayList<>();
        Set<String> processedNodeIds = new HashSet<>();
        int maxHops = ragConfig.getRetrieval().getMaxGraphHops();
        long timeoutMs = ragConfig.getRetrieval().getGraphTimeoutMs();

        for (String entity : entities) {
            for (Long kbId : knowledgeBaseIds) {
                try {
                    // 使用超时控制
                    List<RagChunk> entityChunks = CompletableFuture.supplyAsync(() ->
                                    retrieveEntitySubgraph(entity, kbId, maxHops, processedNodeIds))
                            .get(timeoutMs, TimeUnit.MILLISECONDS);

                    chunks.addAll(entityChunks);
                } catch (Exception e) {
                    log.warn("[GraphRagService] 复杂图检索超时或失败: {}, kbId: {}", entity, kbId, e);
                }
            }
        }

        // 如果多个实体之间有关联，尝试查找它们之间的路径
        if (entities.size() > 1) {
            chunks.addAll(findInterEntityPaths(entities, knowledgeBaseIds, processedNodeIds));
        }

        log.info("[GraphRagService] 复杂图检索完成，结果数量: {}", chunks.size());
        return chunks;
    }

    /**
     * 检索实体的子图
     */
    private List<RagChunk> retrieveEntitySubgraph(String entity, Long kbId, int maxHops,
                                                    Set<String> processedNodeIds) {
        List<RagChunk> chunks = new ArrayList<>();

        // 搜索节点
        List<Neo4jKnowledgeNode> nodes = neo4jService.searchNodesByName(entity);

        for (Neo4jKnowledgeNode node : nodes) {
            if (processedNodeIds.contains(node.getId())) {
                continue;
            }
            processedNodeIds.add(node.getId());

            // 添加节点本身
            chunks.add(RagChunk.fromGraphNode(
                    node.getId(),
                    node.getName(),
                    node.getDescription(),
                    node.getCategory(),
                    kbId
            ));

            // 多跳遍历
            List<Neo4jKnowledgeNode> subgraph = neo4jService.findSubgraph(node.getId(), kbId);
            for (Neo4jKnowledgeNode subNode : subgraph) {
                if (!processedNodeIds.contains(subNode.getId())) {
                    processedNodeIds.add(subNode.getId());
                    chunks.add(RagChunk.fromGraphNode(
                            subNode.getId(),
                            subNode.getName(),
                            subNode.getDescription(),
                            subNode.getCategory(),
                            kbId
                    ));
                }
            }

            // 查找前置知识（多跳推理）
            List<Neo4jKnowledgeNode> prerequisites = neo4jService.findPrerequisites(
                    node.getId(), kbId);
            for (Neo4jKnowledgeNode prereq : prerequisites) {
                if (!processedNodeIds.contains(prereq.getId())) {
                    processedNodeIds.add(prereq.getId());
                    chunks.add(RagChunk.fromGraphNode(
                            prereq.getId(),
                            prereq.getName(),
                            prereq.getDescription(),
                            prereq.getCategory(),
                            kbId
                    ));
                }
            }
        }

        return chunks;
    }

    /**
     * 查找多个实体之间的路径
     */
    private List<RagChunk> findInterEntityPaths(List<String> entities, List<Long> knowledgeBaseIds,
                                                 Set<String> processedNodeIds) {
        List<RagChunk> chunks = new ArrayList<>();

        // 简化实现：查找前两个实体之间的路径
        if (entities.size() < 2) {
            return chunks;
        }

        for (Long kbId : knowledgeBaseIds) {
            try {
                List<Neo4jKnowledgeNode> nodes1 = neo4jService.searchNodesByName(entities.get(0));
                List<Neo4jKnowledgeNode> nodes2 = neo4jService.searchNodesByName(entities.get(1));

                if (!nodes1.isEmpty() && !nodes2.isEmpty()) {
                    String startId = nodes1.get(0).getId();
                    String endId = nodes2.get(0).getId();

                    List<Neo4jKnowledgeNode> path = neo4jService.findShortestPath(
                            startId, endId, kbId);

                    for (Neo4jKnowledgeNode pathNode : path) {
                        if (!processedNodeIds.contains(pathNode.getId())) {
                            processedNodeIds.add(pathNode.getId());
                            chunks.add(RagChunk.fromGraphNode(
                                    pathNode.getId(),
                                    pathNode.getName(),
                                    pathNode.getDescription(),
                                    pathNode.getCategory(),
                                    kbId
                            ));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[GraphRagService] 查找实体间路径失败", e);
            }
        }

        return chunks;
    }
}
