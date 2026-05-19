package com.smartorder.edu.common.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message_outbox")
public class MessageOutbox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String topic;
    private String exchangeName;
    private String routingKey;
    private String payload;
    private String status;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
