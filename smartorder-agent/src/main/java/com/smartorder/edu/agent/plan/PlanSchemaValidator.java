package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
import com.smartorder.edu.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 规划 JSON Schema 校验器
 */
@Slf4j
@Component
public class PlanSchemaValidator {

    private final ToolRegistry toolRegistry;

    public PlanSchemaValidator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 校验并解析规划 JSON
     */
    public JSONObject validateAndParse(String planJson) {
        // 提取 JSON（可能被包裹在 ```json ``` 中）
        String cleanJson = extractJson(planJson);

        JSONObject json;
        try {
            json = JSON.parseObject(cleanJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("规划 JSON 解析失败: " + e.getMessage());
        }

        // 必填字段校验
        require(json, "spaceName", "学习空间名称");
        require(json, "type", "学习空间类型");
        require(json, "goals", "学习目标");

        // phases 校验
        JSONArray phases = json.getJSONArray("phases");
        if (phases == null || phases.isEmpty()) {
            throw new IllegalArgumentException("规划缺少学习阶段（phases）");
        }

        for (int i = 0; i < phases.size(); i++) {
            JSONObject phase = phases.getJSONObject(i);
            require(phase, "phaseName", "阶段" + (i + 1) + "名称");
        }

        log.info("[SchemaValidator] 校验通过: spaceName={}, phases={}", json.getString("spaceName"), phases.size());
        return json;
    }

    // ============ ExecutionPlan 校验 ============

    private static final Set<String> VALID_GATES = Set.of("NORMAL", "CLARIFICATION", "CONFIRMATION");
    private static final Set<String> VALID_RISK_LEVELS = Set.of("READ", "WRITE_DRAFT", "WRITE_CONFIRMED");
    private static final Set<String> VALID_ANSWER_TYPES = Set.of("TEXT", "SINGLE_SELECT", "MULTI_SELECT", "NUMBER");

    /**
     * 校验并解析 ExecutionPlan JSON（PlannerAgent 输出）
     */
    public ExecutionPlan validateAndParseExecutionPlan(String executionPlanJson) {
        String cleanJson = extractJson(executionPlanJson);

        JSONObject json;
        try {
            json = JSON.parseObject(cleanJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("ExecutionPlan JSON 解析失败: " + e.getMessage());
        }

        // 必填字段
        require(json, "userGoal", "用户目标");
        require(json, "steps", "执行步骤列表");

        JSONArray steps = json.getJSONArray("steps");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("执行步骤列表不能为空");
        }
        if (steps.size() > 10) {
            throw new IllegalArgumentException("步骤数量不能超过10个，当前: " + steps.size());
        }

        // 校验每个 step
        Set<String> stepIds = new LinkedHashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            require(step, "stepId", "步骤ID");
            require(step, "description", "步骤描述");
            require(step, "requiredTools", "所需工具");

            String stepId = step.getString("stepId");
            if (!stepId.matches("^step-\\d+[a-z]?$")) {
                throw new IllegalArgumentException("stepId 格式错误（应为 step-N 或 step-Na）: " + stepId);
            }
            if (stepIds.contains(stepId)) {
                throw new IllegalArgumentException("stepId 重复: " + stepId);
            }
            stepIds.add(stepId);

            // 校验 gate
            String gate = step.getString("gate");
            if (gate != null && !VALID_GATES.contains(gate)) {
                throw new IllegalArgumentException("gate 值无效: " + gate + "，应为 NORMAL/CLARIFICATION/CONFIRMATION");
            }

            // 校验 riskLevel
            String riskLevel = step.getString("riskLevel");
            if (riskLevel != null && !VALID_RISK_LEVELS.contains(riskLevel)) {
                throw new IllegalArgumentException("riskLevel 值无效: " + riskLevel + "，应为 READ/WRITE_DRAFT/WRITE_CONFIRMED");
            }

            // 校验 CLARIFICATION 门的 clarificationSlots
            if ("CLARIFICATION".equals(gate)) {
                JSONArray clarSlots = step.getJSONArray("clarificationSlots");
                if (clarSlots == null || clarSlots.isEmpty()) {
                    throw new IllegalArgumentException(
                            "step " + stepId + " 的 gate=CLARIFICATION，必须包含 clarificationSlots 数组");
                }
                if (clarSlots.size() > 5) {
                    throw new IllegalArgumentException(
                            "step " + stepId + " 的 clarificationSlots 不能超过 5 个");
                }
                Set<String> slotKeys = new LinkedHashSet<>();
                for (int k = 0; k < clarSlots.size(); k++) {
                    JSONObject slot = clarSlots.getJSONObject(k);
                    require(slot, "slotKey", "clarificationSlots[" + k + "].slotKey");
                    require(slot, "question", "clarificationSlots[" + k + "].question");
                    require(slot, "answerType", "clarificationSlots[" + k + "].answerType");
                    String answerType = slot.getString("answerType");
                    if (answerType != null && !VALID_ANSWER_TYPES.contains(answerType)) {
                        throw new IllegalArgumentException(
                                "clarificationSlots[" + k + "].answerType 无效: " + answerType
                                        + "，应为 TEXT/SINGLE_SELECT/MULTI_SELECT/NUMBER");
                    }
                    String slotKey = slot.getString("slotKey");
                    if (slotKeys.contains(slotKey)) {
                        throw new IllegalArgumentException("clarificationSlots 中 slotKey 重复: " + slotKey);
                    }
                    slotKeys.add(slotKey);
                }
            }

            // 校验 requiredTools 中的工具名是否真实存在
            JSONArray requiredTools = step.getJSONArray("requiredTools");
            if (requiredTools != null) {
                for (int j = 0; j < requiredTools.size(); j++) {
                    String toolName = requiredTools.getString(j);
                    if (!toolRegistry.contains(toolName)) {
                        throw new IllegalArgumentException(
                                "step " + stepId + " 引用了不存在的工具: " + toolName
                                        + "，可用工具: " + toolRegistry.getRegisteredNames());
                    }
                }
            }
        }

        // 校验 dependsOn 引用
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            JSONArray dependsOn = step.getJSONArray("dependsOn");
            if (dependsOn != null) {
                for (int j = 0; j < dependsOn.size(); j++) {
                    String dep = dependsOn.getString(j);
                    if (!stepIds.contains(dep)) {
                        throw new IllegalArgumentException("dependsOn 引用不存在的 stepId: " + dep);
                    }
                }
            }
        }

        // 拓扑排序检测循环依赖
        checkNoCycles(steps);

        // 业务语义校验：CONFIRMATION 门必须有下游依赖
        checkConfirmationGatesHaveDependents(steps);

        // 解析为 ExecutionPlan 对象
        ExecutionPlan result = JSON.parseObject(cleanJson, ExecutionPlan.class);

        // 后处理：为 CLARIFICATION 步骤同步 clarificationSlotsJson
        for (ExecutionStep step : result.getSteps()) {
            if ("CLARIFICATION".equals(step.getGate()) && step.getClarificationSlots() != null
                    && !step.getClarificationSlots().isEmpty()) {
                step.setClarificationSlotsJson(JSON.toJSONString(step.getClarificationSlots()));
            }
        }

        log.info("[SchemaValidator] ExecutionPlan校验通过: goal={}, steps={}", result.getUserGoal(), result.getSteps().size());
        return result;
    }

    /**
     * 拓扑排序检测循环依赖
     */
    private void checkNoCycles(JSONArray steps) {
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (int i = 0; i < steps.size(); i++) {
            String stepId = steps.getJSONObject(i).getString("stepId");
            adjacency.put(stepId, new ArrayList<>());
            inDegree.putIfAbsent(stepId, 0);
        }

        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String stepId = step.getString("stepId");
            JSONArray dependsOn = step.getJSONArray("dependsOn");
            if (dependsOn != null) {
                for (int j = 0; j < dependsOn.size(); j++) {
                    String dep = dependsOn.getString(j);
                    adjacency.get(dep).add(stepId);
                    inDegree.merge(stepId, 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (String neighbor : adjacency.getOrDefault(current, List.of())) {
                int newDegree = inDegree.merge(neighbor, -1, Integer::sum);
                if (newDegree == 0) queue.add(neighbor);
            }
        }

        if (visited != steps.size()) {
            throw new IllegalArgumentException("ExecutionPlan 包含循环依赖，无法拓扑排序");
        }
    }

    /**
     * 业务语义校验：CONFIRMATION 门步骤必须至少有一个后续步骤依赖它。
     * <p>
     * 若 CONFIRMATION 是最后一步（无下游依赖），它只是一个冗余的 UI 暂停——
     * 学习计划草稿的确认已由 createLearningPlan 工具触发（agent.learning_plan.created 事件），
     * 无需再追加 CONFIRMATION 门。CONFIRMATION 门应只用于守护其后真实有副作用的工具执行。
     */
    private void checkConfirmationGatesHaveDependents(JSONArray steps) {
        // 构建反向邻接：stepId -> 依赖它的 stepId 列表
        Map<String, List<String>> dependents = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            String stepId = steps.getJSONObject(i).getString("stepId");
            dependents.putIfAbsent(stepId, new ArrayList<>());
        }
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String stepId = step.getString("stepId");
            JSONArray dependsOn = step.getJSONArray("dependsOn");
            if (dependsOn != null) {
                for (int j = 0; j < dependsOn.size(); j++) {
                    String dep = dependsOn.getString(j);
                    dependents.get(dep).add(stepId);
                }
            }
        }
        // 校验每个 CONFIRMATION 门至少有一个下游依赖
        for (int i = 0; i < steps.size(); i++) {
            JSONObject step = steps.getJSONObject(i);
            if ("CONFIRMATION".equals(step.getString("gate"))) {
                String stepId = step.getString("stepId");
                if (dependents.get(stepId).isEmpty()) {
                    throw new IllegalArgumentException(
                            "CONFIRMATION 步骤 " + stepId + " 没有后续依赖步骤；"
                                    + "学习计划草稿的确认已由 createLearningPlan 工具触发，无需追加 CONFIRMATION 门。"
                                    + "如需守护后续真实写操作，请在该 CONFIRMATION 步骤之后追加依赖它的工具步骤。");
                }
            }
        }
    }

    // ============ 通用方法 ============

    private void require(JSONObject obj, String key, String displayName) {
        if (!obj.containsKey(key) || obj.get(key) == null || "".equals(obj.getString(key))) {
            throw new IllegalArgumentException("规划缺少必填字段: " + displayName + " (" + key + ")");
        }
    }

    private String extractJson(String response) {
        int jsonStart = response.indexOf("```json");
        if (jsonStart >= 0) {
            jsonStart = response.indexOf('\n', jsonStart) + 1;
            int jsonEnd = response.indexOf("```", jsonStart);
            if (jsonEnd > jsonStart) {
                return response.substring(jsonStart, jsonEnd).trim();
            }
        }
        int braceStart = response.indexOf('{');
        int braceEnd = response.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return response.substring(braceStart, braceEnd + 1).trim();
        }
        return response;
    }
}
