package com.smartorder.edu.common.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.common.model.dto.ExtractedNode;
import com.smartorder.edu.common.model.dto.ExtractedRelation;
import com.smartorder.edu.common.model.dto.KnowledgeGraphExtractResult;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import com.smartorder.edu.common.model.entity.Neo4jKnowledgeNode;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识图谱构建服务
 * 从文档分块中提取知识点和关系，构建到 Neo4j 图数据库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphBuildService {

    private final ChatModel chatModel;
    private final INeo4jKnowledgeGraphService neo4jService;

    private static final String PROMPT_TEMPLATE_PATH = "prompts/knowledge_graph_extract.txt";
    private static final int MAX_CHUNKS_PER_BATCH = 5; // 每批处理的分块数量
    private static final int MAX_CONTENT_LENGTH = 3000; // 每个分块的最大内容长度

    /**
     * 从分块列表构建知识图谱
     *
     * @param chunks           文档分块列表
     * @param knowledgeBaseId  知识库ID
     * @param fileId           文件ID
     * @return 构建的节点数量
     */
    public int buildGraphFromChunks(List<KnowledgeChunk> chunks, Long knowledgeBaseId, Long fileId) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("没有分块数据，跳过图谱构建");
            return 0;
        }

        log.info("开始构建知识图谱，分块数量: {}, 知识库ID: {}, 文件ID: {}", chunks.size(), knowledgeBaseId, fileId);

        // 加载 Prompt 模板
        String templateContent = loadPromptTemplate();
        if (templateContent == null) {
            log.error("无法加载 Prompt 模板");
            return 0;
        }

        PromptTemplate promptTemplate = PromptTemplate.from(templateContent);

        // 合并所有分块内容，分批处理
        List<List<KnowledgeChunk>> batches = partitionChunks(chunks, MAX_CHUNKS_PER_BATCH);

        int totalNodesCreated = 0;
        Set<String> processedNodeNames = new HashSet<>(); // 去重

        for (int i = 0; i < batches.size(); i++) {
            List<KnowledgeChunk> batch = batches.get(i);
            log.info("处理第 {}/{} 批分块，数量: {}", i + 1, batches.size(), batch.size());

            try {
                // 合并批次内容
                String content = mergeChunkContents(batch);

                // 使用 LLM 提取知识点和关系
                KnowledgeGraphExtractResult result = extractKnowledgeGraph(content, promptTemplate);

                if (result.isSuccess() && result.getNodes() != null) {
                    // 去重并创建节点
                    List<ExtractedNode> newNodes = result.getNodes().stream()
                            .filter(node -> !processedNodeNames.contains(node.getName()))
                            .collect(Collectors.toList());

                    if (!newNodes.isEmpty()) {
                        // 创建节点
                        List<Neo4jKnowledgeNode> createdNodes = createNodes(
                                newNodes, knowledgeBaseId, fileId, chunks.get(0).getUserId());

                        // 创建关系
                        if (result.getRelations() != null && !result.getRelations().isEmpty()) {
                            createRelations(result.getRelations(), createdNodes, knowledgeBaseId);
                        }

                        // 记录已处理的节点名称
                        newNodes.forEach(node -> processedNodeNames.add(node.getName()));
                        totalNodesCreated += createdNodes.size();
                    }
                } else {
                    log.warn("第 {} 批分块提取失败: {}", i + 1, result.getErrorMessage());
                }

                // 添加延迟避免 LLM 限流
                if (i < batches.size() - 1) {
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                log.error("处理第 {} 批分块异常", i + 1, e);
            }
        }

        log.info("知识图谱构建完成，创建节点数量: {}", totalNodesCreated);
        return totalNodesCreated;
    }

    /**
     * 使用 LLM 提取知识图谱
     */
    private KnowledgeGraphExtractResult extractKnowledgeGraph(String content, PromptTemplate promptTemplate) {
        try {
            // 截断过长的内容
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "...(内容已截断)";
            }

            Map<String, Object> variables = new HashMap<>();
            variables.put("text_content", content);

            Prompt prompt = promptTemplate.apply(variables);

            String response = chatModel.chat(prompt.text());
            log.debug("LLM 响应: {}", response);

            // 解析 JSON 响应
            return parseExtractResult(response);
        } catch (Exception e) {
            log.error("LLM 提取知识图谱异常", e);
            return KnowledgeGraphExtractResult.failure("LLM 提取异常: " + e.getMessage());
        }
    }

    /**
     * 解析 LLM 提取结果
     */
    private KnowledgeGraphExtractResult parseExtractResult(String response) {
        try {
            // 提取 JSON 部分（处理可能的 markdown 代码块）
            String jsonStr = response;
            if (response.contains("```json")) {
                int start = response.indexOf("```json") + 7;
                int end = response.indexOf("```", start);
                if (end > start) {
                    jsonStr = response.substring(start, end).trim();
                }
            } else if (response.contains("```")) {
                int start = response.indexOf("```") + 3;
                int end = response.indexOf("```", start);
                if (end > start) {
                    jsonStr = response.substring(start, end).trim();
                }
            }

            JSONObject json = JSON.parseObject(jsonStr);

            // 解析节点
            List<ExtractedNode> nodes = new ArrayList<>();
            JSONArray nodesArray = json.getJSONArray("nodes");
            if (nodesArray != null) {
                for (int i = 0; i < nodesArray.size(); i++) {
                    JSONObject nodeJson = nodesArray.getJSONObject(i);
                    ExtractedNode node = new ExtractedNode();
                    node.setName(nodeJson.getString("name"));
                    node.setDescription(nodeJson.getString("description"));
                    node.setCategory(nodeJson.getString("category"));
                    node.setImportance(nodeJson.getInteger("importance"));
                    if (node.getName() != null && !node.getName().isEmpty()) {
                        nodes.add(node);
                    }
                }
            }

            // 解析关系
            List<ExtractedRelation> relations = new ArrayList<>();
            JSONArray relationsArray = json.getJSONArray("relations");
            if (relationsArray != null) {
                for (int i = 0; i < relationsArray.size(); i++) {
                    JSONObject relJson = relationsArray.getJSONObject(i);
                    ExtractedRelation relation = new ExtractedRelation();
                    relation.setSourceName(relJson.getString("sourceName"));
                    relation.setTargetName(relJson.getString("targetName"));
                    relation.setRelationType(relJson.getString("relationType"));
                    relation.setWeight(relJson.getDouble("weight"));
                    relation.setDescription(relJson.getString("description"));
                    if (relation.getSourceName() != null && relation.getTargetName() != null) {
                        relations.add(relation);
                    }
                }
            }

            return KnowledgeGraphExtractResult.success(nodes, relations);
        } catch (Exception e) {
            log.error("解析 LLM 响应异常: {}", response, e);
            return KnowledgeGraphExtractResult.failure("解析响应异常: " + e.getMessage());
        }
    }

    /**
     * 创建节点
     */
    private List<Neo4jKnowledgeNode> createNodes(List<ExtractedNode> extractedNodes, Long knowledgeBaseId,
                                                  Long fileId, Long userId) {
        List<Neo4jKnowledgeNode> nodesToCreate = new ArrayList<>();

        for (ExtractedNode extracted : extractedNodes) {
            // 检查节点是否已存在
            Neo4jKnowledgeNode existingNode = neo4jService.findByNameAndKnowledgeBaseId(
                    extracted.getName(), knowledgeBaseId);

            if (existingNode != null) {
                log.debug("节点已存在，跳过: {}", extracted.getName());
                continue;
            }

            Neo4jKnowledgeNode node = new Neo4jKnowledgeNode();
            node.setId(UUID.randomUUID().toString());
            node.setName(extracted.getName());
            node.setDescription(extracted.getDescription());
            node.setCategory(extracted.getCategory());
            node.setImportance(extracted.getImportance() != null ? extracted.getImportance() : 3);
            node.setKnowledgeBaseId(knowledgeBaseId);
            node.setFileId(fileId);
            node.setSpaceId(knowledgeBaseId); // 使用 knowledgeBaseId 作为 spaceId

            nodesToCreate.add(node);
        }

        if (!nodesToCreate.isEmpty()) {
            return neo4jService.batchCreateNodes(nodesToCreate);
        }

        return Collections.emptyList();
    }

    /**
     * 创建关系
     */
    private void createRelations(List<ExtractedRelation> extractedRelations, List<Neo4jKnowledgeNode> createdNodes,
                                  Long knowledgeBaseId) {
        // 构建节点名称到ID的映射
        Map<String, String> nodeNameToIdMap = createdNodes.stream()
                .collect(Collectors.toMap(Neo4jKnowledgeNode::getName, Neo4jKnowledgeNode::getId, (a, b) -> a));

        for (ExtractedRelation relation : extractedRelations) {
            String sourceId = nodeNameToIdMap.get(relation.getSourceName());
            String targetId = nodeNameToIdMap.get(relation.getTargetName());

            // 如果在当前批次中找不到，尝试从数据库中查找
            if (sourceId == null) {
                Neo4jKnowledgeNode sourceNode = neo4jService.findByNameAndKnowledgeBaseId(
                        relation.getSourceName(), knowledgeBaseId);
                if (sourceNode != null) {
                    sourceId = sourceNode.getId();
                }
            }

            if (targetId == null) {
                Neo4jKnowledgeNode targetNode = neo4jService.findByNameAndKnowledgeBaseId(
                        relation.getTargetName(), knowledgeBaseId);
                if (targetNode != null) {
                    targetId = targetNode.getId();
                }
            }

            if (sourceId != null && targetId != null) {
                try {
                    String relationType = relation.getRelationType() != null ?
                            relation.getRelationType() : "RELATED_TO";
                    Double weight = relation.getWeight() != null ? relation.getWeight() : 0.5;

                    neo4jService.createRelation(sourceId, targetId, relationType, weight, knowledgeBaseId);
                    log.debug("创建关系: {} -> {} ({})", relation.getSourceName(), relation.getTargetName(), relationType);
                } catch (Exception e) {
                    log.warn("创建关系失败: {} -> {}, 错误: {}",
                            relation.getSourceName(), relation.getTargetName(), e.getMessage());
                }
            } else {
                log.debug("跳过关系（节点不存在）: {} -> {}", relation.getSourceName(), relation.getTargetName());
            }
        }
    }

    /**
     * 合并分块内容
     */
    private String mergeChunkContents(List<KnowledgeChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) {
            if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                sb.append(chunk.getContent()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 分批处理分块
     */
    private List<List<KnowledgeChunk>> partitionChunks(List<KnowledgeChunk> chunks, int batchSize) {
        List<List<KnowledgeChunk>> batches = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            batches.add(chunks.subList(i, end));
        }
        return batches;
    }

    /**
     * 加载 Prompt 模板
     */
    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
            try (InputStream inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("加载 Prompt 模板失败: {}", PROMPT_TEMPLATE_PATH, e);
            return null;
        }
    }
}
