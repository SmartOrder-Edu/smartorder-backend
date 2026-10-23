package com.smartorder.edu.student.aiservice;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * 测评题目生成 AI 服务（LangChain4j AiService）。
 *
 * <p>封装与大模型的交互：以流式(Flux)方式返回生成文本，便于上层边生成边推送。
 * 通过 EXPLICIT 手动装配模式，绑定 {@code openAiStreamingChatModel} 流式模型。</p>
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,//手动装配
//        chatModel = "openAiChatModel",//指定模型
        streamingChatModel = "streamingQuestionChatModel"
        //chatMemory = "chatMemory",//配置会话记忆对象
//        chatMemoryProvider = "chatMemoryProvider",//配置会话记忆提供者对象
//        contentRetriever = "contentRetriever",//配置向量数据库检索对象
//        tools = "reservationTool"
)
public interface QuestionAiService {
    /** 根据课程、知识点和题型生成测评题目，并以流式文本返回。*/
//    @SystemMessage("evaluation.generate.questions")
    @SystemMessage("""
            你是专业的教育测评命题专家，负责生成准确、可判分、可教学复盘的客观题。
            
            你必须遵守以下规则：
            1. 严格按照用户消息指定的 JSON 协议输出，不得遗漏必填字段。
            2. 题目答案、逐项解析和知识依据必须相互一致。
            3. 错误选项必须指出具体错误、可能混淆的概念以及正确理解方式。
            4. reasoning_steps 只能包含面向学生的简要解题步骤，不得输出模型内部思维过程。
            5. 只输出合法 JSON，不输出 Markdown、代码块或 JSON 之外的解释文字。
            """)
    Flux<String> generateQuestions(@UserMessage String prompt);
}
