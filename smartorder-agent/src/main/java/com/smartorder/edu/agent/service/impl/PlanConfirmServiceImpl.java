package com.smartorder.edu.agent.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.mapper.AgentPlanDraftMapper;
import com.smartorder.edu.agent.model.dto.AgentRuntimeContext;
import com.smartorder.edu.agent.model.entity.AgentPlanDraft;
import com.smartorder.edu.agent.model.enums.AgentEventType;
import com.smartorder.edu.agent.model.enums.AgentRunMode;
import com.smartorder.edu.agent.service.PlanConfirmService;
import com.smartorder.edu.agent.service.PlanDraftService;
import com.smartorder.edu.agent.service.TraceEventDispatcher;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphEdge;
import com.smartorder.edu.knowledge.domain.entity.KnowledgeGraphNode;
import com.smartorder.edu.knowledge.service.IKnowledgeGraphService;
import com.smartorder.edu.learning.domain.entity.LearningPathEdge;
import com.smartorder.edu.learning.domain.entity.LearningPathNode;
import com.smartorder.edu.learning.domain.entity.Resource;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.dto.LearningSpaceDTO;
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
 *
 * <p>迭代一：草案完整物化。落库结构：
 * <ul>
 *   <li>每个 phase → 一个 stage 类型的 LearningPathNode（带 plannedDurationDays）</li>
 *   <li>每个 phase.knowledgePoints[i] → 一个 knowledge_point 类型 LearningPathNode（parentNodeId 指向 stage，带 kpUid）</li>
 *   <li>knowledgeGraph.nodes → KnowledgeGraphNode（带 kpUid，与 path 节点共享）</li>
 *   <li>phase.resources → Resource(status='planned', source='ai', isAiGenerated=1)，挂在 stage 节点上</li>
 *   <li>edge：stage prerequisite_of stage、stage contains kp、kp prerequisite_of kp（同 phase 内顺序）</li>
 * </ul>
 *
 * <p>幂等：通过行锁 (selectByDraftIdForUpdate) + confirmAndBindSpace 原子更新保证同一草稿只创建一个学习空间。
 *
 * <p>不预生成每日学习任务（由 Master Agent 根据当日情况动态调度）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanConfirmServiceImpl implements PlanConfirmService { 

    private static final Set<String> ALLOWED_RESOURCE_TYPES = Set.of(
            "ppt", "doc", "video", "mind_map", "quiz",
            "case_study", "flashcard_set", "image", "audio", "link"
    );
    private static final int HOURS_PER_WEEK = 7; // 假设：每周学习 7 小时（用于阶段 estimatedDurationMinutes 估算）

    private final PlanDraftService draftService;
    private final AgentPlanDraftMapper draftMapper;
    private final ILearningSpaceService spaceService;
    private final ILearningPathNodeService nodeService;
    private final ILearningPathEdgeService edgeService;
    private final IResourceService resourceService;
    private final IKnowledgeGraphService kgService;
    private final TraceEventDispatcher dispatcher;

    /**
     * 确认学习计划草稿，创建学习空间（空间 + 学习路径 + 知识图谱 + 计划资源）。
     *
     * <p>必须在 {@code AgentPlanController} 调用 {@link PlanDraftService#validateForConfirm} 通过后再调用本方法。
     * 本方法内部再做一次行锁 + 状态检查防 TOCTOU。
     */
    @Transactional(rollbackFor = Exception.class)
    public LearningSpaceVO createLearningSpace(String draftId, Long userId,
                                               String conversationId,
                                               SseEmitter emitter, String traceId) {
        AgentRuntimeContext context = AgentRuntimeContext.builder()
                .traceId(traceId)
                .conversationId(conversationId)
                .userId(userId)
                .mode(AgentRunMode.PLAN_AND_EXECUTE)
                .sequence(new AtomicInteger(0))
                .build();

        try {
            // 0. 行锁草稿，防并发确认
            AgentPlanDraft draft = draftMapper.selectByDraftIdForUpdate(draftId);
            if (draft == null) {
                throw new IllegalArgumentException("学习计划草稿不存在: " + draftId);
            }

            // 0.1 幂等回查：若已确认，直接返回已创建的 space
            if ("CONFIRMED".equals(draft.getStatus()) && draft.getCreatedSpaceId() != null) {
                LearningSpaceVO existing = spaceService.getSpaceById(userId, draft.getCreatedSpaceId());
                log.info("[PlanConfirm] 草稿已确认过，幂等返回 existing space: spaceId={}", existing.getId());
                dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                        "type", "space_already_created",
                        "spaceId", existing.getId(),
                        "spaceName", existing.getName(),
                        "message", "学习空间已经创建过了，直接进入即可。"
                ));
                dispatcher.complete(emitter, context);
                return existing;
            }

            if (!"PENDING".equals(draft.getStatus())) {
                throw new IllegalStateException("草稿状态非 PENDING，无法确认: status=" + draft.getStatus());
            }

            JSONObject plan = JSONObject.parseObject(draft.getPlanJson());
            if (plan == null) {
                throw new IllegalStateException("草稿 planJson 为空: draftId=" + draftId);
            }

            // 1. 后端补 kp_uid（同名 KP 共享同一 UID）
            Map<String, String> kpNameToUid = assignKpUids(plan);
            log.info("[PlanConfirm] 已分配 {} 个知识点 UID", kpNameToUid.size());

            // 2. 创建学习空间
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "正在创建学习空间"
            ));
            LearningSpaceVO spaceVO = createSpace(userId, plan);
            Long spaceId = spaceVO.getId();
            log.info("[PlanConfirm] 学习空间已创建: spaceId={}", spaceId);

            // 3. 创建学习路径节点（两层：stage + knowledge_point）和边
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "正在创建学习路径"
            ));
            Map<Integer, Long> phaseIndexToStageId = createPathNodesAndEdges(spaceId, plan, kpNameToUid);

            // 4. 保存知识图谱（带 kp_uid）
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "正在保存知识图谱"
            ));
            saveKnowledgeGraph(spaceId, plan, kpNameToUid);

            // 5. 保存阶段资源（status=planned）
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "message", "正在登记阶段资源"
            ));
            int resCount = saveResources(spaceId, phaseIndexToStageId, plan);
            log.info("[PlanConfirm] 已登记 {} 个计划中资源", resCount);

            // 6. 原子标记草稿已确认 + 绑定 spaceId（CAS 防并发）
            int affected = draftMapper.confirmAndBindSpace(draftId, spaceId);
            if (affected == 0) {
                // 理论上行锁已防住并发，到这里说明状态被其它途径改了
                throw new IllegalStateException("草稿状态在事务内被异常变更，回滚: draftId=" + draftId);
            }

            // 7. 通知前端
            dispatcher.emit(emitter, context, AgentEventType.STATUS, Map.of(
                    "type", "space_created",
                    "spaceId", spaceId,
                    "spaceName", spaceVO.getName(),
                    "message", "学习空间「" + spaceVO.getName() + "」已创建完成！"
            ));

            int phaseCount = plan.getJSONArray("phases") != null ? plan.getJSONArray("phases").size() : 0;
            int kpCount = kpNameToUid.size();
            dispatcher.emit(emitter, context, AgentEventType.TEXT_DELTA, Map.of(
                    "text", "学习空间「" + spaceVO.getName() + "」已创建完成！\n"
                            + "包含 " + phaseCount + " 个学习阶段、" + kpCount + " 个知识点，知识图谱已就绪。\n"
                            + "你可以进入学习空间开始学习了！"
            ));

            dispatcher.complete(emitter, context);
            log.info("[PlanConfirm] 学习空间创建完成: spaceId={}", spaceId);

            return spaceVO;
        } catch (Exception e) {
            log.error("[PlanConfirm] 执行失败: draftId={}", draftId, e);
            throw e;
        }
    }

    // ============ Private Methods ============

    /**
     * 后端给 plan_json 中所有出现的 KP 名分配 UID（同名 KP 共享同一 UUID）。
     * 来源：phase.knowledgePoints[*]、knowledgeGraph.nodes[*].name。
     * 副作用：把 uid 写回 JSON——
     *   - knowledgeGraph.nodes[i] 增加 kpUid 字段
     *   - 不修改 phases.knowledgePoints（仍是字符串数组），但通过 kpNameToUid 在 createPathNodesAndEdges 里补上
     */
    private Map<String, String> assignKpUids(JSONObject plan) {
        Map<String, String> nameToUid = new LinkedHashMap<>();

        // 1. 从 phases.knowledgePoints 收集
        JSONArray phases = plan.getJSONArray("phases");
        if (phases != null) {
            for (int i = 0; i < phases.size(); i++) {
                JSONObject phase = phases.getJSONObject(i);
                JSONArray kps = phase.getJSONArray("knowledgePoints");
                if (kps != null) {
                    for (int j = 0; j < kps.size(); j++) {
                        String name = kps.getString(j);
                        if (name != null && !name.isBlank()) {
                            nameToUid.computeIfAbsent(name, k -> UUID.randomUUID().toString());
                        }
                    }
                }
            }
        }

        // 2. 从 knowledgeGraph.nodes 收集（可能有 phases 里没出现的额外节点）并写回 uid
        JSONObject kg = plan.getJSONObject("knowledgeGraph");
        if (kg != null) {
            JSONArray kgNodes = kg.getJSONArray("nodes");
            if (kgNodes != null) {
                for (int i = 0; i < kgNodes.size(); i++) {
                    JSONObject n = kgNodes.getJSONObject(i);
                    String name = n.getString("name");
                    if (name == null || name.isBlank()) continue;
                    String uid = nameToUid.computeIfAbsent(name, k -> UUID.randomUUID().toString());
                    n.put("kpUid", uid);
                }
            }
        }
        return nameToUid;
    }

    private LearningSpaceVO createSpace(Long userId, JSONObject plan) {
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
        return spaceService.createSpace(userId, spaceDTO);
    }

    /**
     * 创建两层路径节点（stage + knowledge_point）以及它们之间的依赖/包含边。
     *
     * @return phaseIndex → stage node id 映射，供资源挂载使用
     */
    private Map<Integer, Long> createPathNodesAndEdges(Long spaceId, JSONObject plan,
                                                        Map<String, String> kpNameToUid) {
        Map<Integer, Long> phaseIndexToStageId = new LinkedHashMap<>();
        JSONArray phases = plan.getJSONArray("phases");
        if (phases == null || phases.isEmpty()) {
            return phaseIndexToStageId;
        }

        // 1. 第一遍：建 stage 节点
        List<LearningPathNode> stageNodes = new ArrayList<>();
        List<Integer> phaseIndexList = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            JSONObject phase = phases.getJSONObject(i);
            int phaseIndex = phase.getIntValue("phaseIndex");
            int weeks = phase.getIntValue("weeks");

            LearningPathNode stage = new LearningPathNode();
            stage.setSpaceId(spaceId);
            stage.setParentNodeId(null);
            stage.setKpUid(null);
            stage.setTitle(phase.getString("phaseName"));
            // description 改为空（原来塞 KP 列表，现在 KP 已独立成节点）
            stage.setDescription("");
            stage.setNodeType("stage");
            // sortOrder 给子 KP 留空间：每个 phase 占用 [phaseIndex*1000, phaseIndex*1000+999]
            stage.setSortOrder(phaseIndex * 1000);
            // ★ 修复时长换算 BUG：每周按 HOURS_PER_WEEK 小时计 → 分钟
            stage.setEstimatedDurationMinutes(weeks * HOURS_PER_WEEK * 60);
            stage.setPlannedDurationDays(weeks * 7);
            stage.setEstimatedDurationDays(weeks * 7);
            stage.setActualDurationDays(null);
            stage.setDifficultyLevel(1 + i);
            stage.setIsMilestone(i == phases.size() - 1 ? 1 : 0);

            stageNodes.add(stage);
            phaseIndexList.add(phaseIndex);
        }
        List<LearningPathNode> savedStages = nodeService.batchInsert(stageNodes);
        for (int i = 0; i < savedStages.size(); i++) {
            phaseIndexToStageId.put(phaseIndexList.get(i), savedStages.get(i).getId());
        }

        // 2. 第二遍：建 knowledge_point 子节点
        List<LearningPathNode> kpNodes = new ArrayList<>();
        // 为后续建 KP 之间的 prerequisite 边记录顺序：phaseIndex → 该 phase 内 KP nodes（顺序）
        Map<Integer, List<LearningPathNode>> phaseKpNodes = new LinkedHashMap<>();

        for (int i = 0; i < phases.size(); i++) {
            JSONObject phase = phases.getJSONObject(i);
            int phaseIndex = phase.getIntValue("phaseIndex");
            Long stageId = phaseIndexToStageId.get(phaseIndex);
            if (stageId == null) continue;

            JSONArray kps = phase.getJSONArray("knowledgePoints");
            if (kps == null || kps.isEmpty()) continue;

            List<LearningPathNode> thisPhaseKps = new ArrayList<>();
            for (int j = 0; j < kps.size(); j++) {
                String kpName = kps.getString(j);
                if (kpName == null || kpName.isBlank()) continue;
                String kpUid = kpNameToUid.get(kpName);

                LearningPathNode kp = new LearningPathNode();
                kp.setSpaceId(spaceId);
                kp.setParentNodeId(stageId);
                kp.setKpUid(kpUid);
                kp.setTitle(kpName);
                kp.setDescription("");
                kp.setNodeType("knowledge_point");
                kp.setSortOrder(phaseIndex * 1000 + j + 1);
                kp.setEstimatedDurationMinutes(null);
                kp.setPlannedDurationDays(null);
                kp.setEstimatedDurationDays(null);
                kp.setActualDurationDays(null);
                kp.setDifficultyLevel(1 + i);
                kp.setIsMilestone(0);
                kpNodes.add(kp);
                thisPhaseKps.add(kp);
            }
            phaseKpNodes.put(phaseIndex, thisPhaseKps);
        }
        if (!kpNodes.isEmpty()) {
            nodeService.batchInsert(kpNodes);
        }

        // 3. 建边
        List<LearningPathEdge> edges = new ArrayList<>();

        // 3.1 stage → stage（prerequisite_of），按 phaseIndex 顺序
        List<Integer> sortedPhaseIndices = new ArrayList<>(phaseIndexToStageId.keySet());
        Collections.sort(sortedPhaseIndices);
        for (int i = 0; i < sortedPhaseIndices.size() - 1; i++) {
            Long fromId = phaseIndexToStageId.get(sortedPhaseIndices.get(i));
            Long toId = phaseIndexToStageId.get(sortedPhaseIndices.get(i + 1));
            if (fromId != null && toId != null) {
                edges.add(buildEdge(spaceId, fromId, toId, "prerequisite_of"));
            }
        }

        // 3.2 stage contains kp
        for (Map.Entry<Integer, List<LearningPathNode>> entry : phaseKpNodes.entrySet()) {
            Long stageId = phaseIndexToStageId.get(entry.getKey());
            if (stageId == null) continue;
            for (LearningPathNode kp : entry.getValue()) {
                if (kp.getId() != null) {
                    edges.add(buildEdge(spaceId, stageId, kp.getId(), "contains"));
                }
            }
        }

        // 3.3 同 phase 内 KP 之间 prerequisite_of（按数组顺序）
        for (List<LearningPathNode> thisPhaseKps : phaseKpNodes.values()) {
            for (int i = 0; i < thisPhaseKps.size() - 1; i++) {
                LearningPathNode from = thisPhaseKps.get(i);
                LearningPathNode to = thisPhaseKps.get(i + 1);
                if (from.getId() != null && to.getId() != null) {
                    edges.add(buildEdge(spaceId, from.getId(), to.getId(), "prerequisite_of"));
                }
            }
        }

        if (!edges.isEmpty()) {
            edgeService.batchInsert(edges);
        }

        return phaseIndexToStageId;
    }

    private LearningPathEdge buildEdge(Long spaceId, Long fromId, Long toId, String edgeType) {
        LearningPathEdge edge = new LearningPathEdge();
        edge.setSpaceId(spaceId);
        edge.setFromNodeId(fromId);
        edge.setToNodeId(toId);
        edge.setEdgeType(edgeType);
        return edge;
    }

    private void saveKnowledgeGraph(Long spaceId, JSONObject plan, Map<String, String> kpNameToUid) {
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
                String name = n.getString("name");
                if (name == null || name.isBlank()) continue;

                KnowledgeGraphNode node = new KnowledgeGraphNode();
                node.setSpaceId(spaceId);
                node.setName(name);
                node.setKpUid(kpNameToUid.get(name));
                node.setDescription(n.getString("description") != null ? n.getString("description") : "");
                node.setCategory(n.getString("category") != null ? n.getString("category") : "concept");
                node.setImportance(normalizeImportance(n.get("importance")));
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
                if (fromId == null || toId == null) {
                    log.warn("[PlanConfirm] KG 边引用了不存在的节点: from={}, to={}",
                            e.getString("from"), e.getString("to"));
                    continue;
                }
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

    /**
     * 把 plan.phases[*].resources 落进 resource 表（status=planned，由 Resource Agent 异步生成实际内容）。
     */
    private int saveResources(Long spaceId, Map<Integer, Long> phaseIndexToStageId, JSONObject plan) {
        JSONArray phases = plan.getJSONArray("phases");
        if (phases == null || phases.isEmpty()) return 0;

        List<Resource> toInsert = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            JSONObject phase = phases.getJSONObject(i);
            int phaseIndex = phase.getIntValue("phaseIndex");
            Long stageId = phaseIndexToStageId.get(phaseIndex);
            if (stageId == null) continue;

            JSONArray resources = phase.getJSONArray("resources");
            if (resources == null || resources.isEmpty()) continue;

            for (int j = 0; j < resources.size(); j++) {
                JSONObject r = resources.getJSONObject(j);
                String title = r.getString("title");
                if (title == null || title.isBlank()) continue;

                String type = normalizeResourceType(r.getString("resourceType"));

                Resource res = new Resource();
                res.setSpaceId(spaceId);
                res.setPathNodeId(stageId);
                res.setTaskId(null);
                res.setTitle(title);
                res.setDescription("");
                res.setResourceType(type);
                res.setUrl(""); // 草案阶段无 URL；resourceService.batchInsertPlanned 会兜底
                res.setSource("ai");
                res.setIsAiGenerated(1);
                res.setStatus("planned");
                toInsert.add(res);
            }
        }
        if (toInsert.isEmpty()) return 0;
        resourceService.batchInsertPlanned(toInsert);
        return toInsert.size();
    }

    private String normalizeResourceType(String raw) {
        if (raw == null) return "doc";
        String t = raw.trim().toLowerCase();
        return ALLOWED_RESOURCE_TYPES.contains(t) ? t : "doc";
    }

    static int normalizeImportance(Object raw) {
        if (raw == null) {
            return 3;
        }
        if (raw instanceof Number number) {
            return clampImportance(number.intValue());
        }

        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return 3;
        }

        try {
            return clampImportance((int) Math.round(Double.parseDouble(text)));
        } catch (NumberFormatException ignored) {
            String normalized = text.toLowerCase(Locale.ROOT);
            if (normalized.contains("核心") || normalized.contains("极高")
                    || normalized.contains("最高") || normalized.contains("very high")) {
                return 5;
            }
            if (normalized.contains("高") || normalized.contains("重要")
                    || normalized.contains("high")) {
                return 4;
            }
            if (normalized.contains("中") || normalized.contains("一般")
                    || normalized.contains("medium")) {
                return 3;
            }
            if (normalized.contains("低") || normalized.contains("基础")
                    || normalized.contains("low")) {
                return 2;
            }
            if (normalized.contains("了解") || normalized.contains("可选")
                    || normalized.contains("optional")) {
                return 1;
            }
            log.warn("[PlanConfirm] importance 无法识别，使用默认值 3: raw={}", raw);
            return 3;
        }
    }

    private static int clampImportance(int value) {
        if (value < 1) {
            return 1;
        }
        if (value > 5) {
            return 5;
        }
        return value;
    }
}
