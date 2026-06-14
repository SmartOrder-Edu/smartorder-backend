package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.model.dto.ExecutionPlan;
import com.smartorder.edu.agent.model.dto.ExecutionStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 规划 JSON Schema 校验器
 */
@Slf4j
@Component
public class PlanSchemaValidator {

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

        // 解析为 ExecutionPlan 对象
        ExecutionPlan result = JSON.parseObject(cleanJson, ExecutionPlan.class);
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
