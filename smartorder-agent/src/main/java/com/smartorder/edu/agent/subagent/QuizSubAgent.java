package com.smartorder.edu.agent.subagent;

import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 测评生成子智能体——生成测试题、练习题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizSubAgent implements SubAgent {

    private final ChatModel chatLanguageModel;

    @Override
    public String name() {
        return "测评智能体";
    }

    @Override
    public String description() {
        return "负责生成测试题、练习题和错题分析";
    }

    @Override
    public SubAgentResult run(SubAgentTask task, AgentRuntimeContext parentContext, SseEmitter emitter) {
        log.info("[QuizSubAgent] 开始执行: taskId={}", task.getTaskId());

        try {
            String prompt = """
                    你是一个学习测评专家。
                    请根据以下需求，生成练习题和测试建议：

                    需求：%s

                    输出格式：
                    1. 题目类型建议（选择题/填空题/简答题）
                    2. 3-5 道样题（含答案）
                    3. 难度分级（基础/进阶/挑战）
                    4. 建议的测评频率
                    """.formatted(task.getTaskDescription());

            String result = chatLanguageModel.chat(prompt);

            return SubAgentResult.builder()
                    .taskId(task.getTaskId())
                    .agentName(name())
                    .success(true)
                    .summary("已生成 " + countQuestions(result) + " 道样题")
                    .data(result)
                    .build();

        } catch (Exception e) {
            log.error("[QuizSubAgent] 执行失败: taskId={}", task.getTaskId(), e);
            return SubAgentResult.builder()
                    .taskId(task.getTaskId())
                    .agentName(name())
                    .success(false)
                    .summary("测评生成失败")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private String countQuestions(String result) {
        int count = 0;
        for (String line : result.split("\n")) {
            if (line.matches("^\\d+\\..*[？?].*")) count++;
        }
        return count > 0 ? String.valueOf(count) : "若干";
    }
}
