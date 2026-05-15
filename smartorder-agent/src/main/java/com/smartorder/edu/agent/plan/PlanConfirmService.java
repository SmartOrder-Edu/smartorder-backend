package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.model.entity.AgentResourceLog;
import com.smartorder.edu.agent.mapper.AgentResourceLogMapper;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.service.IKnowledgeGraphService;
import com.smartorder.edu.learning.dto.LearningSpaceDTO;
import com.smartorder.edu.learning.domain.entity.LearningPathEdge;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.Task;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规划确认服务——用户确认规划后的执行阶段
 *
 * 执行步骤：
 * 创建空间 → 创建路径节点/边 → 创建任务 → 保存知识图谱 → 生成第一阶段资源 → 登记后续资源
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanConfirmService {

    private final PlanDraftService draftService;
    private final ILearningSpaceService spaceService;
    private final ILearningPathNodeService nodeService;
    private final ILearningPathEdgeService edgeService;
    private final ITaskService taskService;
    private final IKnowledgeGraphService kgService;
    private final AgentResourceLogMapper resourceLogMapper;
    private final TraceEventDispatcher dispatcher;

    /**
     * 确认并执行学习规划
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmAndExecute(String draftId, Long userId, String conversationId,
                                   SseEmitter emitter, String traceId) {
        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .traceId(traceId)
                .conversationId(conversationId)
                .userId(userId)
                .sequence(new AtomicInteger(0))
                .build();

        try {
            dispatcher.emit(emitter, context, AgentEventType.TRACE_STARTED, Map.of(
                    "mode", "PLAN_EXECUTE"
            ));

            // 1. 获取并确认草稿
            JSONObject plan = draftService.confirmAndGet(draftId, userId);

            // 2. 创建学习空间
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "第1步：创建学习空间"
            ));

            LearningSpaceDTO spaceDTO = new LearningSpaceDTO();
            spaceDTO.setName(plan.getString("spaceName"));
            spaceDTO.setType(plan.getString("type") != null ? plan.getString("type") : "course");
            spaceDTO.setDescription(plan.getString("description"));
            spaceDTO.setGoals(plan.getJSONArray("goals").toJavaList(String.class).toString());
            spaceDTO.setStartDate(plan.getString("startDate") != null
                    ? LocalDate.parse(plan.getString("startDate")) : LocalDate.now());

            LearningSpaceVO spaceVO = spaceService.createSpace(userId, spaceDTO);
            Long spaceId = spaceVO.getId();
            log.info("[PlanConfirm] 学习空间已创建: spaceId={}, name={}", spaceId, spaceVO.getName());

            // 3. 创建学习路径节点和边
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "第2步：创建学习路径和任务"
            ));

            Map<Integer, Long> phaseIndexToNodeId = createPathNodesAndEdges(spaceId, plan);

            // 4. 创建任务
            createTasks(userId, spaceId, plan, phaseIndexToNodeId);

            // 5. 保存知识图谱
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "第3步：保存知识图谱"
            ));

            saveKnowledgeGraph(spaceId, plan);

            // 6. 生成第一阶段资源 + 登记后续资源
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "第4步：准备学习资源"
            ));

            JSONArray phases = plan.getJSONArray("phases");
            for (int i = 0; i < phases.size(); i++) {
                JSONObject phase = phases.getJSONObject(i);
                int phaseIndex = phase.getIntValue("phaseIndex");
                JSONArray resources = phase.getJSONArray("resources");

                if (resources == null || resources.isEmpty()) continue;

                for (int j = 0; j < resources.size(); j++) {
                    JSONObject resource = resources.getJSONObject(j);
                    AgentResourceLog logEntity = new AgentResourceLog();
                    logEntity.setSpaceId(spaceId);
                    logEntity.setPhaseIndex(phaseIndex);
                    logEntity.setResourceType(resource.getString("resourceType"));
                    logEntity.setTitle(resource.getString("title"));
                    logEntity.setUserId(userId);

                    if (phaseIndex == 1) {
                        // 第一阶段资源立即生成（后续阶段完善）
                        logEntity.setStatus("PENDING");
                        logEntity.setGenerationParams(JSONObject.toJSONString(Map.of(
                                "knowledgePoints", phase.getJSONArray("knowledgePoints"),
                                "spaceId", spaceId
                        )));
                    } else {
                        logEntity.setStatus("PENDING");
                    }
                    resourceLogMapper.insert(logEntity);
                }
            }

            // 7. 完成
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                    "text", "学习空间「" + spaceVO.getName() + "」已创建完成！\n" +
                            "包含 " + (plan.getJSONArray("phases") != null ? plan.getJSONArray("phases").size() : 0) +
                            " 个学习阶段，第一阶段学习资料准备中。\n" +
                            "你可以进入学习空间开始学习了！"
            ));

            dispatcher.complete(emitter, context);
            log.info("[PlanConfirm] 执行完成: spaceId={}, draftId={}", spaceId, draftId);

        } catch (Exception e) {
            log.error("[PlanConfirm] 执行失败: draftId={}", draftId, e);
            dispatcher.error(emitter, context, e);
        }
    }

    // ============ Private Methods ============

    private Map<Integer, Long> createPathNodesAndEdges(Long spaceId, JSONObject plan) {
        Map<Integer, Long> phaseIndexToNodeId = new LinkedHashMap<>();
        JSONArray phases = plan.getJSONArray("phases");

        if (phases == null || phases.isEmpty()) return phaseIndexToNodeId;

        // 为每个 phase 创建一个 path node
        List<LearningPathNode> nodes = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            JSONObject phase = phases.getJSONObject(i);
            LearningPathNode node = new LearningPathNode();
            node.setSpaceId(spaceId);
            node.setTitle(phase.getString("phaseName"));
            node.setDescription(String.join(", ", phase.getJSONArray("knowledgePoints")
                    .toJavaList(String.class)));
            node.setNodeType("stage");
            node.setSortOrder(phase.getIntValue("phaseIndex"));
            node.setEstimatedDurationMinutes(phase.getIntValue("weeks") * 7 * 60);
            node.setDifficultyLevel(1 + i);
            node.setIsMilestone(i == phases.size() - 1 ? 1 : 0);
            nodes.add(node);
        }

        List<LearningPathNode> saved = nodeService.batchInsert(nodes);
        for (LearningPathNode node : saved) {
            phaseIndexToNodeId.put(node.getSortOrder(), node.getId());
        }

        // 创建 edges（按顺序依赖）
        List<LearningPathEdge> edges = new ArrayList<>();
        for (int i = 1; i <= phases.size(); i++) {
            Long fromId = phaseIndexToNodeId.get(i);
            Long toId = phaseIndexToNodeId.get(i + 1);
            if (fromId != null && toId != null) {
                LearningPathEdge edge = new LearningPathEdge();
                edge.setSpaceId(spaceId);
                edge.setFromNodeId(fromId);
                edge.setToNodeId(toId);
                edge.setEdgeType("prerequisite_of");
                edges.add(edge);
            }
        }
        if (!edges.isEmpty()) {
            edgeService.batchInsert(edges);
        }

        return phaseIndexToNodeId;
    }

    private void createTasks(Long userId, Long spaceId, JSONObject plan, Map<Integer, Long> phaseIndexToNodeId) {
        JSONArray phases = plan.getJSONArray("phases");
        if (phases == null || phases.isEmpty()) return;

        LocalDate startDate = plan.getString("startDate") != null
                ? LocalDate.parse(plan.getString("startDate")) : LocalDate.now();

        List<Task> tasks = new ArrayList<>();
        int sortOrder = 0;

        for (int i = 0; i < phases.size(); i++) {
            JSONObject phase = phases.getJSONObject(i);
            int phaseIndex = phase.getIntValue("phaseIndex");
            Long nodeId = phaseIndexToNodeId.get(phaseIndex);

            JSONArray kps = phase.getJSONArray("knowledgePoints");
            if (kps == null) continue;

            for (int j = 0; j < kps.size(); j++) {
                Task task = new Task();
                task.setUserId(userId);
                task.setSpaceId(spaceId);
                task.setPathNodeId(nodeId);
                task.setTitle("学习：" + kps.getString(j));
                task.setDescription("掌握「" + kps.getString(j) + "」相关知识");
                task.setTaskType("daily");
                task.setPriority(3);
                task.setStatus("pending");
                task.setDueDate(startDate.plusDays((phaseIndex - 1) * 7L + j));
                task.setEstimatedDurationMinutes(60);
                task.setSortOrder(++sortOrder);
                tasks.add(task);
            }
        }

        if (!tasks.isEmpty()) {
            taskService.batchInsert(tasks);
        }
    }

    private void saveKnowledgeGraph(Long spaceId, JSONObject plan) {
        JSONObject kg = plan.getJSONObject("knowledgeGraph");
        if (kg == null) return;

        JSONArray nodesJson = kg.getJSONArray("nodes");
        JSONArray edgesJson = kg.getJSONArray("edges");

        // 保存节点
        Map<String, Long> nameToId = new HashMap<>();
        if (nodesJson != null && !nodesJson.isEmpty()) {
            List<KnowledgeGraphNode> nodes = new ArrayList<>();
            for (int i = 0; i < nodesJson.size(); i++) {
                JSONObject n = nodesJson.getJSONObject(i);
                KnowledgeGraphNode node = new KnowledgeGraphNode();
                node.setSpaceId(spaceId);
                node.setName(n.getString("name"));
                node.setDescription(n.getString("description") != null ? n.getString("description") : "");
                node.setCategory(n.getString("category") != null ? n.getString("category") : "concept");
                node.setImportance(n.getInteger("importance") != null ? n.getInteger("importance") : 3);
                nodes.add(node);
            }
            List<KnowledgeGraphNode> saved = kgService.batchInsertNodes(nodes);
            for (KnowledgeGraphNode node : saved) {
                nameToId.put(node.getName(), node.getId());
            }
        }

        // 保存边
        if (edgesJson != null && !edgesJson.isEmpty()) {
            List<KnowledgeGraphEdge> edges = new ArrayList<>();
            for (int i = 0; i < edgesJson.size(); i++) {
                JSONObject e = edgesJson.getJSONObject(i);
                Long fromId = nameToId.get(e.getString("from"));
                Long toId = nameToId.get(e.getString("to"));
                if (fromId == null || toId == null) continue;

                KnowledgeGraphEdge edge = new KnowledgeGraphEdge();
                edge.setSpaceId(spaceId);
                edge.setFromNodeId(fromId);
                edge.setToNodeId(toId);
                edge.setRelationType(e.getString("type") != null ? e.getString("type") : "prerequisite_of");
                edges.add(edge);
            }
            if (!edges.isEmpty()) {
                kgService.batchInsertEdges(edges);
            }
        }
    }
}
