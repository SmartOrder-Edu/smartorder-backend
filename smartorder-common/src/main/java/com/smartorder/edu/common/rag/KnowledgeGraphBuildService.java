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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
     * 使用 LLM 提取知识图谱（带重试机制）
     */
    private KnowledgeGraphExtractResult extractKnowledgeGraph(String content, PromptTemplate promptTemplate) {
        int maxRetries = 2;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 截断过长的内容
                String truncatedContent = content;
                if (content.length() > MAX_CONTENT_LENGTH) {
                    truncatedContent = content.substring(0, MAX_CONTENT_LENGTH) + "...(内容已截断)";
                }

                Map<String, Object> variables = new HashMap<>();
                variables.put("text_content", truncatedContent);

                Prompt prompt = promptTemplate.apply(variables);

                String response = chatModel.chat(prompt.text());
                log.info("LLM 响应 (尝试 {}/{}): {}", attempt + 1, maxRetries + 1,
                        response.length() > 500 ? response.substring(0, 500) + "..." : response);

                // 解析 JSON 响应
                KnowledgeGraphExtractResult result = parseExtractResult(response);

                // 解析成功或最后一次尝试，返回结果
                if (result.isSuccess() || attempt == maxRetries) {
                    return result;
                }

                log.warn("JSON 解析失败，准备重试 (尝试 {}/{}): {}", attempt + 1, maxRetries + 1,
                        result.getErrorMessage());
                Thread.sleep(500); // 短暂延迟后重试

            } catch (Exception e) {
                log.error("LLM 提取知识图谱异常 (尝试 {}/{}): {}", attempt + 1, maxRetries + 1, e.getMessage());
                if (attempt == maxRetries) {
                    return KnowledgeGraphExtractResult.failure("LLM 提取异常: " + e.getMessage());
                }
            }
        }

        return KnowledgeGraphExtractResult.failure("达到最大重试次数");
    }

    /**
     * 解析 LLM 提取结果（增强版）
     */
    private KnowledgeGraphExtractResult parseExtractResult(String response) {
        try {
            // 1. 清理响应内容
            String cleanedResponse = cleanResponse(response);

            // 2. 提取 JSON 字符串
            String jsonStr = extractJsonFromResponse(cleanedResponse);

            if (jsonStr == null || jsonStr.isEmpty()) {
                log.warn("无法从 LLM 响应中提取 JSON，原始响应: {}",
                        response.length() > 300 ? response.substring(0, 300) + "..." : response);
                return KnowledgeGraphExtractResult.failure("无法提取有效的 JSON 内容");
            }

            // 3. 解析 JSON
            JSONObject json = JSON.parseObject(jsonStr);

            // 4. 解析节点
            List<ExtractedNode> nodes = new ArrayList<>();
            JSONArray nodesArray = json.getJSONArray("nodes");
            if (nodesArray != null) {
                for (int i = 0; i < nodesArray.size(); i++) {
                    try {
                        JSONObject nodeJson = nodesArray.getJSONObject(i);
                        ExtractedNode node = new ExtractedNode();
                        node.setName(nodeJson.getString("name"));
                        node.setDescription(nodeJson.getString("description"));
                        node.setCategory(nodeJson.getString("category"));
                        node.setImportance(nodeJson.getInteger("importance"));
                        if (node.getName() != null && !node.getName().isEmpty()) {
                            nodes.add(node);
                        }
                    } catch (Exception e) {
                        log.warn("解析第 {} 个节点失败: {}", i, e.getMessage());
                    }
                }
            }

            // 5. 解析关系
            List<ExtractedRelation> relations = new ArrayList<>();
            JSONArray relationsArray = json.getJSONArray("relations");
            if (relationsArray != null) {
                for (int i = 0; i < relationsArray.size(); i++) {
                    try {
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
                    } catch (Exception e) {
                        log.warn("解析第 {} 个关系失败: {}", i, e.getMessage());
                    }
                }
            }

            log.info("成功解析知识图谱: {} 个节点, {} 个关系", nodes.size(), relations.size());
            return KnowledgeGraphExtractResult.success(nodes, relations);

        } catch (Exception e) {
            log.error("解析 LLM 响应异常，响应内容: {}", response.length() > 300 ?
                    response.substring(0, 300) + "..." : response, e);
            return KnowledgeGraphExtractResult.failure("解析响应异常: " + e.getMessage());
        }
    }

    /**
     * 清理响应内容（移除可能干扰 JSON 解析的字符）
     */
    private String cleanResponse(String response) {
        if (response == null) return "";

        // 移除 BOM 标记
        response = response.replaceFirst("^\\uFEFF", "");

        // 移除零宽字符
        response = response.replaceAll("[\\u200B\\u200C\\u200D\\uFEFF]", "");

        // 统一换行符
        response = response.replace("\r\n", "\n").replace("\r", "\n");

        return response.trim();
    }

    /**
     * 从响应中提取 JSON 字符串（增强版）
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // 策略1: 提取 ```json ... ``` 代码块
        Pattern jsonCodeBlockPattern = Pattern.compile("```json\\s*\\n?([\\s\\S]*?)\\n?\\s*```");
        Matcher matcher = jsonCodeBlockPattern.matcher(response);
        if (matcher.find()) {
            String jsonStr = matcher.group(1).trim();
            if (isValidJsonStart(jsonStr)) {
                return jsonStr;
            }
        }

        // 策略2: 提取 ``` ... ``` 代码块（无 json 标记）
        Pattern codeBlockPattern = Pattern.compile("```\\s*\\n?([\\s\\S]*?)\\n?\\s*```");
        matcher = codeBlockPattern.matcher(response);
        if (matcher.find()) {
            String jsonStr = matcher.group(1).trim();
            if (isValidJsonStart(jsonStr)) {
                return jsonStr;
            }
        }

        // 策略3: 使用正则表达式匹配完整的 JSON 对象
        Pattern jsonObjectPattern = Pattern.compile("\\{[\\s\\S]*\\}");
        matcher = jsonObjectPattern.matcher(response);
        if (matcher.find()) {
            String jsonStr = matcher.group().trim();
            // 验证是否包含预期的字段
            if (jsonStr.contains("\"nodes\"") || jsonStr.contains("\"relations\"")) {
                return jsonStr;
            }
        }

        // 策略4: 尝试直接解析整个响应
        if (isValidJsonStart(response.trim())) {
            return response.trim();
        }

        return null;
    }

    /**
     * 检查字符串是否以有效的 JSON 开头
     */
    private boolean isValidJsonStart(String str) {
        if (str == null || str.isEmpty()) return false;
        str = str.trim();
        return str.startsWith("{") || str.startsWith("[");
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
