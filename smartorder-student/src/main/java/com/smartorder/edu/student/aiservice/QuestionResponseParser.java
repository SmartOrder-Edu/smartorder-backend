package com.smartorder.edu.student.aiservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 大模型响应解析器：从 LLM 返回文本中提取 questions JSON 数组。
 *
 * <p>职责单一：处理 markdown 代码块围栏清理、JSON 解析、questions 数组提取。
 * 不负责单题字段映射（由 {@link QuestionJsonMapper} 完成）。</p>
 *
 * @author 马强
 * @since 2026-07-12
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * 解析大模型响应文本为 questions JSON 数组节点。
     *
     * <p>兼容大模型在 JSON 外包 ```json 代码块围栏的情况。</p>
     *
     * @param response LLM 返回的原始文本
     * @return questions 数组节点
     * @throws BusinessException 响应为空、JSON 非法或缺少 questions 数组时抛出
     */
    public JsonNode parseQuestionsArray(String response) {
        if (response == null) {
            throw new BusinessException("LLM响应为空");
        }

        String json = stripCodeFence(response);

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode questions = root.get("questions");
            if (questions == null || !questions.isArray()) {
                throw new BusinessException("LLM响应JSON缺少questions数组");
            }
            return questions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析 LLM 响应失败: {}", response, e);
            throw new BusinessException("解析LLM响应为有效JSON失败");
        }
    }

    /** 去除 ```json ... ``` 代码块围栏 */
    private String stripCodeFence(String response) {
        String json = response.strip();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return json;
    }
}
