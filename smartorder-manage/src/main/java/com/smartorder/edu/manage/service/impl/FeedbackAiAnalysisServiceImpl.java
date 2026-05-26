package com.smartorder.edu.manage.service.impl;

import com.smartorder.edu.manage.service.FeedbackAiAnalysisService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackAiAnalysisServiceImpl implements FeedbackAiAnalysisService {

    // Spring会自动注入在AgentConfiguration中配置的ChatModel
    private final ChatModel chatModel;

    @Override
    public String analyzeFeedback(String content, Integer type) {
        String typeStr = switch (type) {
            case 1 -> "产品评价";
            case 2 -> "功能建议";
            case 3 -> "Bug反馈";
            default -> "其他反馈";
        };

        String prompt = String.format(
            "你是一个智能客服与运营助手系统（集成于SmartOrder平台）。\n" +
            "用户提交了一条类型为【%s】的反馈。你需要分析该反馈是否真实、可靠、有效。\n" +
            "过滤标准：\n" +
            "1. 纯乱码、无意义字符、灌水词（如“啊啊啊”、“111”）视为无效。\n" +
            "2. 包含恶意辱骂、严重违规词汇视为无效。\n" +
            "3. 如果是建设性意见、真实的体验评价或明确的Bug描述，视为有效。\n\n" +
            "用户反馈内容如下：\n\"%s\"\n\n" +
            "请严格以JSON格式返回分析结果，不要包含任何额外的markdown标记或说明文字。格式要求如下：\n" +
            "{\n" +
            "  \"isValid\": true/false,\n" +
            "  \"reason\": \"你的分析理由（简明扼要，控制在50字内）\",\n" +
            "  \"score\": 0到100的质量打分\n" +
            "}",
            typeStr, content
        );

        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from(prompt)).build());
            String result = response.aiMessage().text();
            
            // 清理可能存在的markdown格式
            if (result.startsWith("```json")) {
                result = result.substring(7);
            }
            if (result.endsWith("```")) {
                result = result.substring(0, result.length() - 3);
            }
            return result.trim();
        } catch (Exception e) {
            log.error("AI分析反馈异常: ", e);
            // 降级处理：AI异常时默认通过初审，交由人工复核
            return "{\"isValid\": true, \"reason\": \"AI分析服务降级，默认通过\", \"score\": 50}";
        }
    }
}
