package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.assessment.domain.entity.Assessment;
import com.smartorder.edu.assessment.domain.entity.AssessmentQuestion;
import com.smartorder.edu.assessment.mapper.AssessmentMapper;
import com.smartorder.edu.assessment.mapper.AssessmentQuestionMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成测试题工具——调用 LLM 生成测试题并保存到 Assessment
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateQuizTool implements ToolHandler {

    private final ChatModel chatLanguageModel;
    private final AssessmentMapper assessmentMapper;
    private final AssessmentQuestionMapper assessmentQuestionMapper;

    @Override
    public String name() {
        return "generateQuiz";
    }

    @Override
    public String description() {
        return "根据知识点生成练习题或测试题。可以指定题目数量、题型（选择题/填空题/简答题）和难度。生成后自动保存为测评，可用于课后练习或阶段测试";
    }

    @Override
    public Class<?> requestType() {
        return GenerateQuizRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.QUIZ;
    }

    @Override
    public Object execute(Object request, ToolInvokeContext context) {
        GenerateQuizRequest req = (GenerateQuizRequest) request;
        Long userId = context.getUserId();
        int questionCount = req.getQuestionCount() > 0 ? req.getQuestionCount() : 5;

        log.info("[GenerateQuiz] userId={}, spaceId={}, topic={}, count={}", userId, req.getSpaceId(), req.getTopic(), questionCount);

        // 调用 LLM 生成试题
        String prompt = buildQuizPrompt(req.getTopic(), questionCount, req.getQuestionType(), req.getDifficulty());
        String llmResponse = chatLanguageModel.chat(prompt);

        // 解析 LLM 响应并保存
        Assessment assessment = new Assessment();
        assessment.setSpaceId(req.getSpaceId());
        assessment.setPathNodeId(req.getPathNodeId());
        assessment.setTitle(req.getTopic() + " - 练习题");
        assessment.setDescription("由AI自动生成的" + req.getTopic() + "练习题");
        assessment.setAssessmentType(req.getQuestionType() != null ? req.getQuestionType() : "mixed");
        assessment.setTotalScore((double) questionCount * 10);
        assessment.setPassScore((double) questionCount * 6);
        assessment.setIsDeleted(0);
        assessmentMapper.insert(assessment);

        // 将LLM响应作为题目保存（简化实现，实际应解析JSON结构）
        AssessmentQuestion question = new AssessmentQuestion();
        question.setAssessmentId(assessment.getId());
        question.setQuestionType(req.getQuestionType() != null ? req.getQuestionType() : "mixed");
        question.setQuestionText(llmResponse);
        question.setScore((double) questionCount * 10);
        question.setSortOrder(1);
        question.setIsDeleted(0);
        assessmentQuestionMapper.insert(question);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assessmentId", assessment.getId());
        result.put("title", assessment.getTitle());
        result.put("questionCount", questionCount);
        result.put("totalScore", assessment.getTotalScore());
        result.put("message", "测试题已生成，用户可以在测评中心查看和作答");
        return result;
    }

    private String buildQuizPrompt(String topic, int count, String type, Integer difficulty) {
        String typeDesc = type != null ? type : "选择题";
        String diffDesc = difficulty != null ? "难度等级" + difficulty + "/5" : "中等难度";
        return String.format("""
                你是一位专业的教育测评专家。请根据以下要求生成练习题：

                知识点：%s
                题目数量：%d道
                题型：%s
                难度：%s

                要求：
                1. 每道题标注题号
                2. 选择题需要4个选项（A/B/C/D）
                3. 标注正确答案
                4. 附上简要解析
                5. 如果是指定的难度等级，题目难度要有梯度

                请直接输出题目，不要输出其他说明文字。
                """, topic, count, typeDesc, diffDesc);
    }

    @Data
    public static class GenerateQuizRequest {
        private Long spaceId;
        private Long pathNodeId;
        private String topic;
        private int questionCount = 5;
        private String questionType;
        private Integer difficulty;
    }
}
