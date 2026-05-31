package com.smartorder.edu.student.config;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfig {

    /*@Bean
    public ConsultantService consultantService(){
        ConsultantService consultantService = AiServices.builder(ConsultantService.class)
                .chatModel(model)
                .build();
        return consultantService;
    }*/

    //构建会话记忆对象
    @Bean
    public ChatMemory chatMemory(){
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
        return memory;
    }


}
