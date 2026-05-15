package com.smartorder.edu.common.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.smartorder.edu.common.model.entity.ChatMessage;
import com.smartorder.edu.common.mapper.ChatMessageMapper;
import com.smartorder.edu.common.service.IChatMessageService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements IChatMessageService {

    @Override
    public List<ChatMessage> getConversations(Long teacherId) {
        return baseMapper.selectRecentConversations(teacherId);
    }

    @Override
    public Page<ChatMessage> getChatHistory(Long currentTeacherId, Long targetTeacherId, Integer page, Integer size) {
        Page<ChatMessage> pageParam = new Page<>(page, size);

        // 查询逻辑：发件人是我且收件人是对方，或者发件人是对方且收件人是我
        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(ChatMessage::getSenderId, currentTeacherId).eq(ChatMessage::getReceiverId, targetTeacherId))
                .or(w -> w.eq(ChatMessage::getSenderId, targetTeacherId).eq(ChatMessage::getReceiverId, currentTeacherId))
                .orderByDesc(ChatMessage::getCreateTime); // 通常按时间倒序，前端接收后自行反转或正序排列展示

        return this.page(pageParam, wrapper);
    }

    @Override
    public void markAsRead(Long senderId, Long receiverId) {
        // 更新逻辑：把对方发给我的、状态为未读（0）的消息，状态更新为已读（1）
        LambdaUpdateWrapper<ChatMessage> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(ChatMessage::getSenderId, senderId)
                .eq(ChatMessage::getReceiverId, receiverId)
                .eq(ChatMessage::getStatus, 0)
                .set(ChatMessage::getStatus, 1);

        this.update(updateWrapper);
    }

    @Override
    public void recallMessage(Long messageId, Long teacherId) {
        ChatMessage message = this.getById(messageId);
        if (message == null) {
            throw new RuntimeException("消息不存在");
        }

        // 1. 校验是否为本人的消息（防越权操作）
        if (!message.getSenderId().equals(teacherId)) {
            throw new RuntimeException("非法操作，无权撤回该消息");
        }

        // 2. 校验是否超时（通常限制为 2 分钟以内可以撤回）
        if (message.getCreateTime().plusMinutes(2).isBefore(LocalDateTime.now())) {
            throw new RuntimeException("发送时间已超过2分钟，无法撤回");
        }

        // 3. 更改状态为撤回(2)，并记录撤回时间
        message.setStatus(2);
        message.setRevoke_at(LocalDateTime.now());
        // 可以考虑清空消息内容，或者保留记录但不返回给前端
        // message.setContent("一条消息被撤回");

        this.updateById(message);
    }
}