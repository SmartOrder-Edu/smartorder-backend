package com.smartorder.edu.student.aiservice;

import com.smartorder.edu.student.domain.dto.GenerateQuestionDTO;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,//手动装配
//        chatModel = "openAiChatModel",//指定模型
        streamingChatModel = "openAiStreamingChatModel"
        //chatMemory = "chatMemory",//配置会话记忆对象
//        chatMemoryProvider = "chatMemoryProvider",//配置会话记忆提供者对象
//        contentRetriever = "contentRetriever",//配置向量数据库检索对象
//        tools = "reservationTool"
)
public interface QuestionAiService {
    /** 根据课程、知识点和题型生成测评题目，并保存到题库。*/
//    @SystemMessage("evaluation.generate.questions")
    @SystemMessage("""
            你是一个专业的练习题生成专家。
            你必须严格按照用户要求生成 JSON。
            不要输出 Markdown。
            不要输出解释性文字。
            """)
    Flux<String> generateQuestions(@UserMessage String prompt);
}
