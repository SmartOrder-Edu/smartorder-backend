package com.smartorder.edu.common.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务呼叫类（这部分可以持久化到数据库，作为聊天记录的“通话日志”）：
 *
 * VOICE_CALL_REQUEST / VIDEO_CALL_REQUEST (发起呼叫)
 *
 * CALL_ACCEPT (接受呼叫)
 *
 * CALL_REJECT (拒绝呼叫)
 *
 * CALL_CANCEL (呼叫方取消)
 *
 * CALL_END (挂断通话)
 */


/**
WebRTC 信令类（非常关键：此类消息绝对不要存入数据库，只做内存级的瞬时转发，否则会瞬间打爆你的 MySQL）：

WEBRTC_OFFER (发送 SDP Offer)

WEBRTC_ANSWER (发送 SDP Answer)

WEBRTC_ICE_CANDIDATE (交换网络候选者)
 */

@Data
@TableName("chat_message")
public class ChatMessage {
//    @TableId(type = IdType.AUTO)
    private Long id;

    private Long senderId;      // 发送方教师ID
    private Long receiverId;    // 接收方教师ID

    private String msgType;     // 消息类型: TEXT(文本/Emoji), IMAGE, VOICE, FILE
    private String content;     // 文本内容或文件URL

    private Integer status;     // 状态: 0-未读, 1-已读, 2-撤回

    private LocalDateTime createTime; // 发送时间
    private LocalDateTime revoke_at;//撤回时间
}


