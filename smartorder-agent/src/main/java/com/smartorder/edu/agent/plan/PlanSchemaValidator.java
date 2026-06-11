package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smartorder.edu.agent.model.dto.TaskItem;
import com.smartorder.edu.agent.model.dto.TaskList;
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

    // ============ TaskList 校验 ============

    private static final Set<String> VALID_GATES = Set.of("NORMAL", "CLARIFICATION", "CONFIRMATION");
    private static final Set<String> VALID_RISK_LEVELS = Set.of("READ", "WRITE_DRAFT", "WRITE_CONFIRMED");

    /**
     * 校验并解析 TaskList JSON（PlannerAgent 输出）
     */
    public TaskList validateAndParseTaskList(String taskListJson) {
        String cleanJson = extractJson(taskListJson);

        JSONObject json;
        try {
            json = JSON.parseObject(cleanJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("TaskList JSON 解析失败: " + e.getMessage());
        }

        // 必填字段
        require(json, "userGoal", "用户目标");
        require(json, "tasks", "任务列表");

        JSONArray tasks = json.getJSONArray("tasks");
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("任务列表不能为空");
        }
        if (tasks.size() > 10) {
            throw new IllegalArgumentException("任务数量不能超过10个，当前: " + tasks.size());
        }

        // 校验每个 task
        Set<String> taskIds = new LinkedHashSet<>();
        for (int i = 0; i < tasks.size(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            require(task, "taskId", "任务ID");
            require(task, "description", "任务描述");
            require(task, "requiredTools", "所需工具");

            String taskId = task.getString("taskId");
            if (!taskId.matches("^task-\\d+$")) {
                throw new IllegalArgumentException("taskId 格式错误（应为 task-N）: " + taskId);
            }
            if (taskIds.contains(taskId)) {
                throw new IllegalArgumentException("taskId 重复: " + taskId);
            }
            taskIds.add(taskId);

            // 校验 gate
            String gate = task.getString("gate");
            if (gate != null && !VALID_GATES.contains(gate)) {
                throw new IllegalArgumentException("gate 值无效: " + gate + "，应为 NORMAL/CLARIFICATION/CONFIRMATION");
            }

            // 校验 riskLevel
            String riskLevel = task.getString("riskLevel");
            if (riskLevel != null && !VALID_RISK_LEVELS.contains(riskLevel)) {
                throw new IllegalArgumentException("riskLevel 值无效: " + riskLevel + "，应为 READ/WRITE_DRAFT/WRITE_CONFIRMED");
            }
        }

        // 校验 dependsOn 引用
        for (int i = 0; i < tasks.size(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            JSONArray dependsOn = task.getJSONArray("dependsOn");
            if (dependsOn != null) {
                for (int j = 0; j < dependsOn.size(); j++) {
                    String dep = dependsOn.getString(j);
                    if (!taskIds.contains(dep)) {
                        throw new IllegalArgumentException("dependsOn 引用不存在的 taskId: " + dep);
                    }
                }
            }
        }

        // 拓扑排序检测循环依赖
        checkNoCycles(tasks);

        // 解析为 TaskList 对象
        TaskList result = JSON.parseObject(cleanJson, TaskList.class);
        log.info("[SchemaValidator] TaskList校验通过: goal={}, tasks={}", result.getUserGoal(), result.getTasks().size());
        return result;
    }

    /**
     * 拓扑排序检测循环依赖
     */
    private void checkNoCycles(JSONArray tasks) {
        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (int i = 0; i < tasks.size(); i++) {
            String taskId = tasks.getJSONObject(i).getString("taskId");
            adjacency.put(taskId, new ArrayList<>());
            inDegree.putIfAbsent(taskId, 0);
        }

        for (int i = 0; i < tasks.size(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            String taskId = task.getString("taskId");
            JSONArray dependsOn = task.getJSONArray("dependsOn");
            if (dependsOn != null) {
                for (int j = 0; j < dependsOn.size(); j++) {
                    String dep = dependsOn.getString(j);
                    adjacency.get(dep).add(taskId);
                    inDegree.merge(taskId, 1, Integer::sum);
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

        if (visited != tasks.size()) {
            throw new IllegalArgumentException("TaskList 包含循环依赖，无法拓扑排序");
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
