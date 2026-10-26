package com.smartorder.edu.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息 VO。
 * <p>
 * 只加字段不删字段：旧前端读取 id / role / content / reasoningSegments 等不受影响。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {
    /** 消息主键（雪花 ID），前端收到后用它替换本地临时消息 ID */
    private Long id;
    private String role;
    private String content;
    private String toolName;
    private String traceId;
    private String reasoningContent;
    /** 思考分段 JSON（与工具调用交错），用于前端还原实时展示效果 */
    private String reasoningSegments;
    /** 本条消息关联的附件文件ID列表，JSON数组格式如[1,2,3] */
    private String attachmentFileIds;
    private LocalDateTime createTime;

    /** 对外消息 UUID（= 前端生成的 message_uuid，写入 conversation_message.public_id） */
    private String publicId;
    /** 会话内序号，从 1 开始 */
    private Integer sequenceNo;
    /** RUNNING / COMPLETED / FAILED / CANCELLED */
    private String status;
    /** 父消息ID（assistant 指向触发它的 user 消息） */
    private Long parentId;
    /** 第几次生成 */
    private Integer attemptNo;
    /** 是否当前可见版本 */
    private Integer isVisible;
    /** 占位行创建时间（RUNNING 起点），前端据此判断"生成中断" */
    private LocalDateTime startedAt;
    /** 失败原因 */
    private String errorMessage;
}
