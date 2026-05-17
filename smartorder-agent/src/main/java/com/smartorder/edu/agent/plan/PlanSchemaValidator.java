package com.smartorder.edu.agent.plan;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
