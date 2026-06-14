package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.route.AgentRunMode;
import com.smartorder.edu.agent.trace.AgentEventType;
import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import com.smartorder.edu.agent.trace.TraceEventDispatcher;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.service.IKnowledgeGraphService;
import com.smartorder.edu.learning.dto.LearningSpaceDTO;
import com.smartorder.edu.learning.domain.entity.LearningPathEdge;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
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
 * 学习计划确认服务——用户审阅学习计划后的执行阶段。
 * <p>
 * 根据学习计划草稿创建学习空间实体：学习空间 → 学习路径节点/边 → 知识图谱。
 * 不预生成每日学习任务（由 Master Agent 根据当日情况动态调度）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanConfirmService {

    private final PlanDraftService draftService;
    private final ILearningSpaceService spaceService;
    private final ILearningPathNodeService nodeService;
    private final ILearningPathEdgeService edgeService;
    private final IKnowledgeGraphService kgService;
    private final TraceEventDispatcher dispatcher;

    /**
     * 确认学习计划草稿，创建学习空间（空间 + 学习路径 + 知识图谱）。
     * <p>
     * 不预生成每日学习任务；任务由 Master Agent 根据学生当日情况动态调度。
     */
    @Transactional(rollbackFor = Exception.class)
    public LearningSpaceVO createLearningSpace(String draftId, Long userId,
                                               String conversationId,
                                               SseEmitter emitter, String traceId) {
        JSONObject plan = draftService.getLearningPlanJson(draftId);

        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .traceId(traceId)
                .conversationId(conversationId)
                .userId(userId)
                .mode(AgentRunMode.PLAN_AND_EXECUTE)
                .sequence(new AtomicInteger(0))
                .build();

        try {
            // 1. 创建学习空间
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "正在创建学习空间"
            ));

            LearningSpaceDTO spaceDTO = new LearningSpaceDTO();
            spaceDTO.setName(plan.getString("spaceName"));
            spaceDTO.setType(plan.getString("type") != null ? plan.getString("type") : "course");
            spaceDTO.setDescription(plan.getString("description"));

            JSONArray goalsArr = plan.getJSONArray("goals");
            spaceDTO.setGoals(goalsArr != null ? goalsArr.toJSONString() : "[]");

            try {
                spaceDTO.setStartDate(plan.getString("startDate") != null
                        ? LocalDate.parse(plan.getString("startDate")) : LocalDate.now());
            } catch (Exception e) {
                log.warn("[PlanConfirm] startDate 解析失败: {}", plan.getString("startDate"));
                spaceDTO.setStartDate(LocalDate.now());
            }

            if (plan.getString("targetEndDate") != null) {
                try {
                    spaceDTO.setTargetEndDate(LocalDate.parse(plan.getString("targetEndDate")));
                } catch (Exception e) {
                    log.warn("[PlanConfirm] targetEndDate 解析失败: {}", plan.getString("targetEndDate"));
                }
            }

            LearningSpaceVO spaceVO = spaceService.createSpace(userId, spaceDTO);
            Long spaceId = spaceVO.getId();
            log.info("[PlanConfirm] 学习空间已创建: spaceId={}", spaceId);

            // 2. 创建学习路径节点和边
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "正在创建学习路径"
            ));
            createPathNodesAndEdges(spaceId, plan);

            // 3. 保存知识图谱
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "正在保存知识图谱"
            ));
            saveKnowledgeGraph(spaceId, plan);

            // 标记草稿为已确认
            draftService.cancelDraft(draftId, userId);

            // 通知前端
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "type", "space_created",
                    "spaceId", spaceId,
                    "spaceName", spaceVO.getName(),
                    "message", "学习空间「" + spaceVO.getName() + "」已创建完成！"
            ));

            int phaseCount = plan.getJSONArray("phases") != null ? plan.getJSONArray("phases").size() : 0;
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                    "text", "学习空间「" + spaceVO.getName() + "」已创建完成！\n"
                            + "包含 " + phaseCount + " 个学习阶段，知识图谱已就绪。\n"
                            + "你可以进入学习空间开始学习了！"
            ));

            dispatcher.complete(emitter, context);
            log.info("[PlanConfirm] 学习空间创建完成: spaceId={}", spaceId);

            return spaceVO;
        } catch (Exception e) {
            log.error("[PlanConfirm] 执行失败: draftId={}", draftId, e);
            dispatcher.error(emitter, context, e);
            throw e;
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
            JSONArray kps = phase.getJSONArray("knowledgePoints");
            node.setDescription(kps != null ? String.join(", ", kps.toJavaList(String.class)) : "");
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
