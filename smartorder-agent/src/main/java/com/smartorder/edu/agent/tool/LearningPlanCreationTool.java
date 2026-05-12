package com.smartorder.edu.agent.tool;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.agent.dto.GeneratedPlan;
import com.smartorder.edu.agent.dto.PlanCreationResult;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.service.IKnowledgeGraphService;
import com.smartorder.edu.learning.domain.entity.*;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.learning.service.*;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 学习计划创建工具 — Agent 通过此工具将 LLM 生成的计划持久化为学习空间 + 路径 + 任务 + 知识图谱
 *
 * 工作流程：
 * 1. 接收计划参数（名称、描述、目标、时间范围等）
 * 2. 调用 LLM 生成结构化的 JSON 学习计划
 * 3. 解析 JSON 并批量写入数据库
 * 4. 返回创建结果供 Agent 告知用户
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningPlanCreationTool {

    private final LearningSpaceMapper spaceMapper;
    private final ILearningPathNodeService nodeService;
    private final ILearningPathEdgeService edgeService;
    private final ITaskService taskService;
    private final IKnowledgeGraphService kgService;
    private final ChatLanguageModel chatLanguageModel;

    @Tool("创建一个完整的学习计划（学习空间）。当用户确认要开始学习某个目标时调用。" +
          "参数 planDescription 应包含：用户的目标描述、可用时间、学科领域、学术水平等关键信息。" +
          "Agent 应先通过 getStudentProfile 了解学生，再调用此工具创建计划。")
    @Transactional(rollbackFor = Exception.class)
    public String createLearningPlan(Long userId, String planDescription) {
        log.info("[LearningPlanCreationTool] 用户 {} 请求创建学习计划: {}", userId, planDescription);

        // Step 1: 调用 LLM 生成结构化计划
        GeneratedPlan plan = generatePlanWithLLM(userId, planDescription);
        if (plan == null) {
            return "{\"error\": \"计划生成失败，请稍后重试\"}";
        }

        // Step 2: 创建 LearningSpace
        LearningSpace space = createSpace(userId, plan);

        // Step 3: 批量创建 Path Nodes（记录 sortOrder → id 映射）
        Map<Integer, Long> sortOrderToId = new HashMap<>();
        List<LearningPathNode> nodes = createPathNodes(space.getId(), plan, sortOrderToId);

        // Step 4: 创建 Path Edges（依赖关系）
        createPathEdges(space.getId(), plan, sortOrderToId);

        // Step 5: 创建 Tasks（关联到 path nodes）
        createTasks(userId, space.getId(), plan, sortOrderToId);

        // Step 6: 创建知识图谱节点
        createKgNodes(space.getId(), plan);

        PlanCreationResult result = PlanCreationResult.builder()
                .spaceId(space.getId())
                .spaceName(space.getName())
                .spaceType(space.getType())
                .nodeCount(nodes.size())
                .taskCount(plan.getTasks() != null ? plan.getTasks().size() : 0)
                .kgNodeCount(plan.getKgNodes() != null ? plan.getKgNodes().size() : 0)
                .startDate(plan.getStartDate())
                .targetEndDate(plan.getTargetEndDate())
                .build();

        log.info("[LearningPlanCreationTool] 学习计划创建成功: {}", JSON.toJSONString(result));
        return "[PLAN_CREATED]" + JSON.toJSONString(result);
    }

    // ============ Private Methods ============

    private GeneratedPlan generatePlanWithLLM(Long userId, String planDescription) {
        String prompt = buildPlanPrompt(planDescription);
        try {
            String response = chatLanguageModel.chat(prompt);
            log.info("[LearningPlanCreationTool] LLM 原始返回长度: {} 字符", response.length());
            // 提取 JSON 块
            String json = extractJson(response);
            return JSON.parseObject(json, GeneratedPlan.class);
        } catch (Exception e) {
            log.error("[LearningPlanCreationTool] LLM 计划解析失败", e);
            return null;
        }
    }

    private String buildPlanPrompt(String planDescription) {
        return """
            你是一位专业的学习路径规划专家。请根据以下学生信息，生成一个结构化的学习计划。

            ## 学生需求
            %s

            ## 输出格式
            请严格按照以下 JSON Schema 返回（不要包含其他文字，只返回 JSON）：

            {
              "spaceName": "学习空间名称",
              "type": "course|exam|project|career|competition",
              "description": "简要描述，50字内",
              "goals": ["目标1", "目标2"],
              "startDate": "yyyy-MM-dd（今天或明天开始，日期合理即可）",
              "targetEndDate": "yyyy-MM-dd",
              "nodes": [
                {
                  "title": "节点标题",
                  "description": "节点描述",
                  "nodeType": "module|stage|objective|knowledge_point",
                  "estimatedDurationMinutes": 120,
                  "difficultyLevel": 1-5,
                  "sortOrder": 1,
                  "isMilestone": 0或1
                }
              ],
              "edges": [
                {
                  "fromSortOrder": 1,
                  "toSortOrder": 2,
                  "edgeType": "prerequisite_of|recommended_before"
                }
              ],
              "tasks": [
                {
                  "title": "任务标题",
                  "description": "任务描述",
                  "taskType": "daily|milestone|exam|review",
                  "priority": 1-5,
                  "nodeSortOrder": 1,
                  "dayOffset": 0,
                  "estimatedDurationMinutes": 30
                }
              ],
              "kgNodes": [
                {
                  "name": "知识点名称",
                  "description": "简要描述",
                  "category": "concept|skill|topic|method|fact",
                  "importance": 1-5
                }
              ]
            }

            ## 要求
            1. 学习路径节点数量：短期目标3-5个模块，长期目标5-10个模块
            2. 每个模块至少1-2个任务
            3. 合理设置依赖关系（edges），确保学习顺序正确
            4. 任务应具体、可执行
            5. 请确保 JSON 是合法的，可以被解析
            6. 日期使用 yyyy-MM-dd 格式
            """.formatted(planDescription);
    }

    private String extractJson(String response) {
        // 尝试提取 ```json ... ``` 块
        int jsonStart = response.indexOf("```json");
        if (jsonStart >= 0) {
            jsonStart = response.indexOf('\n', jsonStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }
        // 尝试提取 { ... } 块
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1).trim();
        }
        return response;
    }

    private LearningSpace createSpace(Long userId, GeneratedPlan plan) {
        LearningSpace space = new LearningSpace();
        space.setUserId(userId);
        space.setName(plan.getSpaceName());
        space.setType(plan.getType() != null ? plan.getType() : "course");
        space.setDescription(plan.getDescription());
        space.setGoals(JSON.toJSONString(plan.getGoals()));
        space.setStartDate(plan.getStartDate() != null ? LocalDate.parse(plan.getStartDate()) : LocalDate.now());
        space.setTargetEndDate(plan.getTargetEndDate() != null ? LocalDate.parse(plan.getTargetEndDate()) : null);
        space.setProgress(0.0);
        space.setStatus("active");
        space.setSortOrder(0);
        space.setIsDeleted(0);
        spaceMapper.insert(space);
        return space;
    }

    private List<LearningPathNode> createPathNodes(Long spaceId, GeneratedPlan plan, Map<Integer, Long> sortOrderToId) {
        if (plan.getNodes() == null || plan.getNodes().isEmpty()) return Collections.emptyList();

        List<LearningPathNode> entities = new ArrayList<>();
        for (GeneratedPlan.PlanNode pn : plan.getNodes()) {
            LearningPathNode node = new LearningPathNode();
            node.setSpaceId(spaceId);
            node.setTitle(pn.getTitle());
            node.setDescription(pn.getDescription());
            node.setNodeType(pn.getNodeType() != null ? pn.getNodeType() : "module");
            node.setEstimatedDurationMinutes(pn.getEstimatedDurationMinutes());
            node.setDifficultyLevel(pn.getDifficultyLevel() != null ? pn.getDifficultyLevel() : 1);
            node.setSortOrder(pn.getSortOrder() != null ? pn.getSortOrder() : entities.size() + 1);
            node.setIsMilestone(pn.getIsMilestone() != null ? pn.getIsMilestone() : 0);
            entities.add(node);
        }

        List<LearningPathNode> saved = nodeService.batchInsert(entities);
        for (LearningPathNode node : saved) {
            sortOrderToId.put(node.getSortOrder(), node.getId());
        }
        return saved;
    }

    private void createPathEdges(Long spaceId, GeneratedPlan plan, Map<Integer, Long> sortOrderToId) {
        if (plan.getEdges() == null || plan.getEdges().isEmpty()) return;

        List<LearningPathEdge> entities = new ArrayList<>();
        for (GeneratedPlan.PlanEdge pe : plan.getEdges()) {
            Long fromId = sortOrderToId.get(pe.getFromSortOrder());
            Long toId = sortOrderToId.get(pe.getToSortOrder());
            if (fromId == null || toId == null) continue;

            LearningPathEdge edge = new LearningPathEdge();
            edge.setSpaceId(spaceId);
            edge.setFromNodeId(fromId);
            edge.setToNodeId(toId);
            edge.setEdgeType(pe.getEdgeType() != null ? pe.getEdgeType() : "prerequisite_of");
            entities.add(edge);
        }
        if (!entities.isEmpty()) {
            edgeService.batchInsert(entities);
        }
    }

    private void createTasks(Long userId, Long spaceId, GeneratedPlan plan, Map<Integer, Long> sortOrderToId) {
        if (plan.getTasks() == null || plan.getTasks().isEmpty()) return;

        LocalDate startDate = plan.getStartDate() != null ? LocalDate.parse(plan.getStartDate()) : LocalDate.now();
        List<Task> entities = new ArrayList<>();
        for (int i = 0; i < plan.getTasks().size(); i++) {
            GeneratedPlan.PlanTask pt = plan.getTasks().get(i);
            Task task = new Task();
            task.setUserId(userId);
            task.setSpaceId(spaceId);
            task.setPathNodeId(sortOrderToId.get(pt.getNodeSortOrder()));
            task.setTitle(pt.getTitle());
            task.setDescription(pt.getDescription());
            task.setTaskType(pt.getTaskType() != null ? pt.getTaskType() : "daily");
            task.setPriority(pt.getPriority() != null ? pt.getPriority() : 3);
            task.setStatus("pending");
            task.setDueDate(startDate.plusDays(pt.getDayOffset() != null ? pt.getDayOffset() : 0));
            task.setEstimatedDurationMinutes(pt.getEstimatedDurationMinutes());
            task.setSortOrder(i + 1);
            entities.add(task);
        }
        taskService.batchInsert(entities);
    }

    private void createKgNodes(Long spaceId, GeneratedPlan plan) {
        if (plan.getKgNodes() == null || plan.getKgNodes().isEmpty()) return;

        List<KnowledgeGraphNode> entities = new ArrayList<>();
        for (GeneratedPlan.PlanKgNode pkg : plan.getKgNodes()) {
            KnowledgeGraphNode node = new KnowledgeGraphNode();
            node.setSpaceId(spaceId);
            node.setName(pkg.getName());
            node.setDescription(pkg.getDescription() != null ? pkg.getDescription() : "");
            node.setCategory(pkg.getCategory() != null ? pkg.getCategory() : "concept");
            node.setImportance(pkg.getImportance() != null ? pkg.getImportance() : 3);
            entities.add(node);
        }
        kgService.batchInsertNodes(entities);
    }
}
