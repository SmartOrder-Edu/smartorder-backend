package com.smartorder.edu.common.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartorder.edu.common.model.entity.ChatMessage;

import java.util.List;

public interface IChatMessageService extends IService<ChatMessage> {

    List<ChatMessage> getConversations(Long teacherId);

    Page<ChatMessage> getChatHistory(Long currentTeacherId, Long targetTeacherId, Integer page, Integer size);

    void markAsRead(Long senderId, Long receiverId);

    void recallMessage(Long messageId, Long teacherId);
}