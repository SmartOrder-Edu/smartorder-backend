package com.smartorder.edu.agent.service;

import com.smartorder.edu.agent.model.dto.ProfileDimensionDecision;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public interface ProfilePromptService {

    List<ChatMessage> buildRouterMessages(String currentUserMessage, List<ChatMessage> conversationHistory);

    List<ChatMessage> buildExtractionMessages(String currentUserMessage, List<ChatMessage> conversationHistory, List<ProfileDimensionDecision> decisions);
}
