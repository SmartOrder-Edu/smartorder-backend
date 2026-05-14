package com.smartorder.edu.agent.subagent;

import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识图谱子智能体——生成知识点和依赖关系
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeGraphSubAgent implements SubAgent {

    private final ChatLanguageModel chatLanguageModel;

    @Override
    public String name() {
        return "知识图谱智能体";
    }

    @Override
    public String description() {
        return "负责生成知识图谱节点和依赖关系";
    }

    @Override
    public SubAgentResult run(SubAgentTask task, AgentRuntimeContext parentContext, SseEmitter emitter) {
        log.info("[KnowledgeGraphSubAgent] 开始执行: taskId={}", task.getTaskId());

        try {
            String prompt = """
                    你是一个知识图谱专家。
                    请根据以下需求，提取核心知识点及其依赖关系：

                    需求：%s

                    输出格式（JSON）：
                    {
                      "nodes": [
                        {"name": "知识点名称", "category": "concept|skill|topic", "importance": 1-5}
                      ],
                      "edges": [
                        {"from": "前置知识点", "to": "后置知识点", "type": "prerequisite_of"}
                      ]
                    }

                    要求：
                    1. 提取 5-10 个核心知识点
                    2. 标注知识点之间的前置依赖关系
                    3. 给每个知识点标注重要程度（1-5）
                    """.formatted(task.getTaskDescription());

            String result = chatLanguageModel.chat(prompt);

            return SubAgentResult.builder()
                    .taskId(task.getTaskId())
                    .agentName(name())
                    .success(true)
                    .summary("已提取 " + countNodes(result) + " 个知识点")
                    .data(result)
                    .build();

        } catch (Exception e) {
            log.error("[KnowledgeGraphSubAgent] 执行失败: taskId={}", task.getTaskId(), e);
            return SubAgentResult.builder()
                    .taskId(task.getTaskId())
                    .agentName(name())
                    .success(false)
                    .summary("知识图谱生成失败")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private String countNodes(String result) {
        int count = 0;
        for (String line : result.split("\n")) {
            if (line.contains("\"name\":")) count++;
        }
        return count > 0 ? String.valueOf(count) : "若干";
    }
}
