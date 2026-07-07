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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.smartorder.edu.common.config.LlmConfig.RAG_CHAT_MODEL_BEAN;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeGraphBuildService {

    private static final String PROMPT_TEMPLATE_PATH = "prompts/knowledge_graph_extract.txt";
    private static final int MAX_CHUNKS_PER_BATCH = 5;
    private static final int MAX_BATCH_CONTENT_LENGTH = 8000;
    private static final int MAX_SINGLE_CHUNK_CONTENT_LENGTH = 1500;

    @Qualifier(RAG_CHAT_MODEL_BEAN)
    private final ChatModel chatModel;

    private final INeo4jKnowledgeGraphService neo4jService;

    public int buildGraphFromChunks(List<KnowledgeChunk> chunks, Long knowledgeBaseId, Long fileId) {
        if (chunks == null || chunks.isEmpty()) {
            log.warn("No chunks found, skip knowledge graph build.");
            return 0;
        }

        log.info("Start building knowledge graph, chunks={}, knowledgeBaseId={}, fileId={}",
                chunks.size(), knowledgeBaseId, fileId);

        String templateContent = loadPromptTemplate();
        if (templateContent == null) {
            log.error("Failed to load prompt template: {}", PROMPT_TEMPLATE_PATH);
            return 0;
        }

        PromptTemplate promptTemplate = PromptTemplate.from(templateContent);
        List<List<KnowledgeChunk>> batches = partitionChunks(chunks, MAX_CHUNKS_PER_BATCH);

        int totalNodesCreated = 0;
        Set<String> processedNodeNames = new HashSet<>();

        for (int i = 0; i < batches.size(); i++) {
            List<KnowledgeChunk> batch = batches.get(i);
            log.info("Processing graph extraction batch {}/{}, chunks={}", i + 1, batches.size(), batch.size());

            try {
                String chunkContext = buildChunkContext(batch);
                KnowledgeGraphExtractResult result = extractKnowledgeGraph(chunkContext, promptTemplate);

                if (!result.isSuccess() || result.getNodes() == null) {
                    log.warn("Graph extraction failed for batch {}: {}", i + 1, result.getErrorMessage());
                    continue;
                }

                List<ExtractedNode> candidateNodes = result.getNodes().stream()
                        .filter(node -> node.getName() != null && !node.getName().isBlank())
                        .filter(node -> processedNodeNames.add(node.getName()))
                        .collect(Collectors.toList());

                List<Neo4jKnowledgeNode> createdNodes = createNodes(
                        candidateNodes, knowledgeBaseId, fileId, chunks.get(0).getUserId());
                totalNodesCreated += createdNodes.size();

                if (result.getRelations() != null && !result.getRelations().isEmpty()) {
                    createRelations(result.getRelations(), createdNodes, knowledgeBaseId);
                }

                if (i < batches.size() - 1) {
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                log.error("Graph extraction batch {} failed", i + 1, e);
            }
        }

        log.info("Knowledge graph build finished, createdNodes={}", totalNodesCreated);
        return totalNodesCreated;
    }

    private KnowledgeGraphExtractResult extractKnowledgeGraph(String chunkContext, PromptTemplate promptTemplate) {
        int maxRetries = 2;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String truncatedContent = truncate(chunkContext, MAX_BATCH_CONTENT_LENGTH);

                Map<String, Object> variables = new HashMap<>();
                variables.put("chunk_context", truncatedContent);
                variables.put("text_content", truncatedContent);

                Prompt prompt = promptTemplate.apply(variables);
                String response = chatModel.chat(prompt.text());
                log.info("LLM graph response (attempt {}/{}): {}", attempt + 1, maxRetries + 1,
                        response.length() > 500 ? response.substring(0, 500) + "..." : response);

                KnowledgeGraphExtractResult result = parseExtractResult(response);
                if (result.isSuccess() || attempt == maxRetries) {
                    return result;
                }

                log.warn("Failed to parse graph JSON, retrying (attempt {}/{}): {}",
                        attempt + 1, maxRetries + 1, result.getErrorMessage());
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("LLM graph extraction error (attempt {}/{}): {}",
                        attempt + 1, maxRetries + 1, e.getMessage());
                if (attempt == maxRetries) {
                    return KnowledgeGraphExtractResult.failure("LLM graph extraction error: " + e.getMessage());
                }
            }
        }

        return KnowledgeGraphExtractResult.failure("Max retry count reached");
    }

    private KnowledgeGraphExtractResult parseExtractResult(String response) {
        try {
            String cleanedResponse = cleanResponse(response);
            String jsonStr = extractJsonFromResponse(cleanedResponse);

            if (jsonStr == null || jsonStr.isEmpty()) {
                log.warn("No valid JSON found in LLM response: {}",
                        response.length() > 300 ? response.substring(0, 300) + "..." : response);
                return KnowledgeGraphExtractResult.failure("No valid JSON found in LLM response");
            }

            JSONObject json = JSON.parseObject(jsonStr);

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
                        node.setSourceChunkIds(readLongList(nodeJson.getJSONArray("sourceChunkIds")));
                        node.setSourceChunkIndexes(readIntegerList(nodeJson.getJSONArray("sourceChunkIndexes")));
                        node.setSourceTitlePaths(readStringList(nodeJson.getJSONArray("sourceTitlePaths")));
                        if (node.getName() != null && !node.getName().isBlank()) {
                            nodes.add(node);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse graph node at index {}: {}", i, e.getMessage());
                    }
                }
            }

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
                        log.warn("Failed to parse graph relation at index {}: {}", i, e.getMessage());
                    }
                }
            }

            log.info("Parsed graph extraction result: nodes={}, relations={}", nodes.size(), relations.size());
            return KnowledgeGraphExtractResult.success(nodes, relations);
        } catch (Exception e) {
            log.error("Failed to parse LLM graph response: {}", response.length() > 300 ?
                    response.substring(0, 300) + "..." : response, e);
            return KnowledgeGraphExtractResult.failure("Failed to parse response: " + e.getMessage());
        }
    }

    private String cleanResponse(String response) {
        if (response == null) {
            return "";
        }
        return response
                .replaceFirst("^\\uFEFF", "")
                .replaceAll("[\\u200B\\u200C\\u200D\\uFEFF]", "")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();
    }

    private String extractJsonFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        Pattern jsonCodeBlockPattern = Pattern.compile("```json\\s*\\n?([\\s\\S]*?)\\n?\\s*```");
        Matcher matcher = jsonCodeBlockPattern.matcher(response);
        if (matcher.find()) {
            String jsonStr = matcher.group(1).trim();
            if (isValidJsonStart(jsonStr)) {
                return jsonStr;
            }
        }

        Pattern codeBlockPattern = Pattern.compile("```\\s*\\n?([\\s\\S]*?)\\n?\\s*```");
        matcher = codeBlockPattern.matcher(response);
        if (matcher.find()) {
            String jsonStr = matcher.group(1).trim();
            if (isValidJsonStart(jsonStr)) {
                return jsonStr;
            }
        }

        Pattern jsonObjectPattern = Pattern.compile("\\{[\\s\\S]*\\}");
        matcher = jsonObjectPattern.matcher(response);
        if (matcher.find()) {
            String jsonStr = matcher.group().trim();
            if (jsonStr.contains("\"nodes\"") || jsonStr.contains("\"relations\"")) {
                return jsonStr;
            }
        }

        if (isValidJsonStart(response.trim())) {
            return response.trim();
        }

        return null;
    }

    private boolean isValidJsonStart(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        str = str.trim();
        return str.startsWith("{") || str.startsWith("[");
    }

    private List<Neo4jKnowledgeNode> createNodes(List<ExtractedNode> extractedNodes, Long knowledgeBaseId,
                                                  Long fileId, Long userId) {
        List<Neo4jKnowledgeNode> nodesToCreate = new ArrayList<>();

        for (ExtractedNode extracted : extractedNodes) {
            Neo4jKnowledgeNode existingNode = neo4jService.findByNameAndKnowledgeBaseId(
                    extracted.getName(), knowledgeBaseId);

            if (existingNode != null) {
                log.debug("Graph node already exists, skip create: {}", extracted.getName());
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
            node.setSpaceId(knowledgeBaseId);
            node.setSourceChunkIds(joinLongs(extracted.getSourceChunkIds()));
            node.setSourceChunkIndexes(joinIntegers(extracted.getSourceChunkIndexes()));
            node.setSourceTitlePaths(joinStrings(extracted.getSourceTitlePaths()));

            nodesToCreate.add(node);
        }

        if (!nodesToCreate.isEmpty()) {
            return neo4jService.batchCreateNodes(nodesToCreate);
        }

        return Collections.emptyList();
    }

    private void createRelations(List<ExtractedRelation> extractedRelations, List<Neo4jKnowledgeNode> createdNodes,
                                  Long knowledgeBaseId) {
        Map<String, String> nodeNameToIdMap = createdNodes.stream()
                .collect(Collectors.toMap(Neo4jKnowledgeNode::getName, Neo4jKnowledgeNode::getId, (a, b) -> a));

        for (ExtractedRelation relation : extractedRelations) {
            String sourceId = resolveNodeId(relation.getSourceName(), knowledgeBaseId, nodeNameToIdMap);
            String targetId = resolveNodeId(relation.getTargetName(), knowledgeBaseId, nodeNameToIdMap);

            if (sourceId != null && targetId != null) {
                try {
                    String relationType = relation.getRelationType() != null ? relation.getRelationType() : "RELATED_TO";
                    Double weight = relation.getWeight() != null ? relation.getWeight() : 0.5;
                    String description = relation.getDescription() != null ? relation.getDescription() : "";

                    neo4jService.createRelation(sourceId, targetId, relationType, weight, knowledgeBaseId, description);
                    log.debug("Created graph relation: {} -> {} ({})",
                            relation.getSourceName(), relation.getTargetName(), relationType);
                } catch (Exception e) {
                    log.warn("Failed to create graph relation: {} -> {}, error={}",
                            relation.getSourceName(), relation.getTargetName(), e.getMessage());
                }
            } else {
                log.debug("Skip relation because node does not exist: {} -> {}",
                        relation.getSourceName(), relation.getTargetName());
            }
        }
    }

    private String resolveNodeId(String nodeName, Long knowledgeBaseId, Map<String, String> nodeNameToIdMap) {
        if (nodeName == null) {
            return null;
        }
        String nodeId = nodeNameToIdMap.get(nodeName);
        if (nodeId != null) {
            return nodeId;
        }
        Neo4jKnowledgeNode node = neo4jService.findByNameAndKnowledgeBaseId(nodeName, knowledgeBaseId);
        return node == null ? null : node.getId();
    }

    private String buildChunkContext(List<KnowledgeChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }

            sb.append("<chunk>\n")
                    .append("chunkId: ").append(chunk.getId()).append('\n')
                    .append("chunkIndex: ").append(chunk.getChunkIndex()).append('\n')
                    .append("parentChunkId: ").append(chunk.getParentChunkId()).append('\n')
                    .append("titlePath: ").append(nullToEmpty(chunk.getTitlePath())).append('\n')
                    .append("sectionTitle: ").append(nullToEmpty(chunk.getSectionTitle())).append('\n')
                    .append("pageNumber: ").append(chunk.getPageNumber()).append('\n')
                    .append("content:\n")
                    .append(truncate(content, MAX_SINGLE_CHUNK_CONTENT_LENGTH))
                    .append("\n</chunk>\n\n");
        }
        return sb.toString().trim();
    }

    private List<List<KnowledgeChunk>> partitionChunks(List<KnowledgeChunk> chunks, int batchSize) {
        List<List<KnowledgeChunk>> batches = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            batches.add(chunks.subList(i, end));
        }
        return batches;
    }

    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
            try (InputStream inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load prompt template: {}", PROMPT_TEMPLATE_PATH, e);
            return null;
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated)";
    }

    private String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<Long> readLongList(JSONArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Long value = array.getLong(i);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private List<Integer> readIntegerList(JSONArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            Integer value = array.getInteger(i);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private List<String> readStringList(JSONArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String value = array.getString(i);
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return new ArrayList<>(result);
    }

    private String joinLongs(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String joinIntegers(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private String joinStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(" | ", values);
    }
}
