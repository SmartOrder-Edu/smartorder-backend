package com.smartorder.edu.agent.subagent;

import com.smartorder.edu.agent.trace.AgentRuntimeContext;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 资源生成子智能体——生成 PPT、文档等学习资源
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceSubAgent implements SubAgent {

    private final ChatLanguageModel chatLanguageModel;

    @Override
    public String name() {
        return "资源生成智能体";
    }

    @Override
    public String description() {
        return "负责生成学习 PPT、文档、思维导图等资源";
    }

    @Override
    public SubAgentResult run(SubAgentTask task, AgentRuntimeContext parentContext, SseEmitter emitter) {
        log.info("[ResourceSubAgent] 开始执行: taskId={}", task.getTaskId());

        try {
            String prompt = """
                    你是一个学习资源生成专家。
                    请根据以下需求，为学习资料生成一份结构化的内容大纲和要点：

                    需求：%s

                    输出格式（纯文本）：
                    1. 资源类型建议（PPT/文档/思维导图）
                    2. 内容大纲（3-5 个章节）
                    3. 每章关键知识点（2-3 个）
                    4. 推荐的学习时长
                    """.formatted(task.getTaskDescription());

            String result = chatLanguageModel.chat(prompt);

            return SubAgentResult.builder()
                    .taskId(task.getTaskId())
                    .agentName(name())
                    .success(true)
                    .summary("已生成 " + extractResourceType(result) + " 内容大纲")
                    .data(result)
                    .build();

        } catch (Exception e) {
            log.error("[ResourceSubAgent] 执行失败: taskId={}", task.getTaskId(), e);
            return SubAgentResult.builder()
                    .taskId(task.getTaskId())
                    .agentName(name())
                    .success(false)
                    .summary("资源生成失败")
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private String extractResourceType(String result) {
        if (result.contains("PPT")) return "PPT";
        if (result.contains("文档")) return "文档";
        if (result.contains("思维导图")) return "思维导图";
        return "综合";
    }
}
