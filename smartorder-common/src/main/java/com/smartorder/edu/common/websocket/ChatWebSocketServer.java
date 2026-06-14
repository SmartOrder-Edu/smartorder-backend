package com.smartorder.edu.common.websocket;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartorder.edu.common.model.dto.ChatMessageDTO;
import com.smartorder.edu.common.model.entity.ChatMessage;
import com.smartorder.edu.common.model.entity.UserFriend;
import com.smartorder.edu.common.mapper.UserFriendMapper;
import com.smartorder.edu.common.service.IChatMessageService;
import com.smartorder.edu.common.util.JwtTokenUtils;
import io.jsonwebtoken.Claims;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ServerEndpoint("/ws/chat/{token}") //
public class ChatWebSocketServer {

    private static final ConcurrentHashMap<Long, Session> onlineSessions = new ConcurrentHashMap<>();

    // 【关键改动 1】：注入 Mapper。由于 WebSocket 是多实例的，必须用 static 变量 + set 方法注入
    private static UserFriendMapper userFriendMapper;

    // 在 ChatWebSocketServer 类顶部添加注入
    private static IChatMessageService chatMessageService;

    private static StringRedisTemplate stringRedisTemplate;

    @Autowired
    public void setStringRedisTemplate(StringRedisTemplate stringRedisTemplate) {
        ChatWebSocketServer.stringRedisTemplate = stringRedisTemplate;
    }

    @Autowired
    public void setUserFriendMapper(UserFriendMapper userFriendMapper) {
        ChatWebSocketServer.userFriendMapper = userFriendMapper;
    }

    @Autowired
    public void setChatMessageService(IChatMessageService chatMessageService) {
        ChatWebSocketServer.chatMessageService = chatMessageService;
    }

    /**
     * 连接建立
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) {
        try {

            // 1. 使用你的 JwtTokenUtils 解析前端传来的 Access Token
            // 注意：这里传入的是短 token 的 Secret
            Claims claims = JwtTokenUtils.getClaimsFromToken(token, JwtTokenUtils.ACCESS_TOKEN_SECRET);

            // 2. 从 Claims 中提取 userId
            // 💡 避坑指南：JWT 序列化时，较小的数字往往会被解析成 Integer，
            // 为了防止强转 Long 报错，建议用下面这种先转 String 再转 Long 的安全写法：
            Object userIdObj = claims.get("userId");
            if (userIdObj == null) {
                throw new IllegalArgumentException("Token 中不包含 userId");
            }
            Long userId = Long.valueOf(userIdObj.toString());

            // 3. 校验通过，绑定用户与 Session
            onlineSessions.put(userId, session);
            session.getUserProperties().put("userId", userId);

            log.info("用户 [{}] 上线，当前在线人数: {}", userId, onlineSessions.size());

            pushRedisOfflineMessages(userId, session);
        } catch (Exception e) {
            // 如果 Token 过期、签名错误或被篡改，getClaimsFromToken 会抛出异常
            log.error("WebSocket 连接失败，Token 无效或已过期: {}", e.getMessage());
            try {
                // 强制关闭该非法连接，并返回错误原因
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "非法连接或身份已过期"));
            } catch (Exception ignored) {
            }
        }
    }

    @OnClose
    public void onClose(Session session) {
        Long userId = (Long) session.getUserProperties().get("userId");
        if (userId != null) {
            onlineSessions.remove(userId);
            log.info("用户 [{}] 下线，当前在线人数: {}", userId, onlineSessions.size());
        }
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        // 【关键改动 2】：拦截心跳包，防止后续的 JSON.parseObject 报错
        if ("ping".equalsIgnoreCase(message) || "heartbeat".equalsIgnoreCase(message)) {
            session.getAsyncRemote().sendText("pong");
            return;
        }

        log.info("收到消息: {}", message);

        try {
            Long realSenderId = (Long) session.getUserProperties().get("userId");
            if (realSenderId == null) return;

            ChatMessageDTO dto = JSON.parseObject(message, ChatMessageDTO.class);
            dto.setSenderId(realSenderId);

            // ================= 【关键改动 3：好友关系拦截校验】 =================
            // 注意视角：查询的是【接收方】的列表里，有没有【发送方】
            LambdaQueryWrapper<UserFriend> query = new LambdaQueryWrapper<>();
            query.eq(UserFriend::getUserId, dto.getReceiverId())
                    .eq(UserFriend::getFriendId, realSenderId);
            UserFriend relation = userFriendMapper.selectOne(query);

            if (relation == null || relation.getStatus() == 2) {
                // 对方把你单删了，或者你们根本没加过好友
                sendSystemTipToSender(session, "对方开启了好友验证，你还不是他（她）的好友，请先发送好友请求");
                return; // 直接 return，不入库，不转发
            }

            if (relation.getStatus() == 3) {
                // 对方把你拉黑了
                sendSystemTipToSender(session, "消息已发出，但被对方拒收");
                return; // 直接 return，不入库，不转发
            }
            // ===============================================================
            // 【新增】：在好友校验通过后，判断是否为音视频通话的业务信令
            if (isCallMessage(dto.getMsgType())) {
                handleCallMessage(dto, session);
                return; // 处理完呼叫信令直接返回，不走下方的普通聊天逻辑
            }
            // 2. 校验通过，持久化到数据库
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setId(IdWorker.getId());
            chatMessage.setSenderId(dto.getSenderId());
            chatMessage.setReceiverId(dto.getReceiverId());
            chatMessage.setMsgType(dto.getMsgType());
            chatMessage.setContent(dto.getContent());
            chatMessage.setStatus(0);
            chatMessage.setCreateTime(LocalDateTime.now());
            chatMessageService.save(chatMessage);

            // 3. 组装推送消息
            dto.setMessageId(chatMessage.getId());
            dto.setCreateTime(chatMessage.getCreateTime().toString());


            // 4. 转发给接收方
            Session receiverSession = onlineSessions.get(dto.getReceiverId());
            if (receiverSession != null && receiverSession.isOpen()) {
                receiverSession.getAsyncRemote().sendText(JSON.toJSONString(dto));

                // 推送成功后，可以把消息状态改为已投递
                chatMessageService.lambdaUpdate()
                        .eq(ChatMessage::getId, chatMessage.getId())
                        .set(ChatMessage::getStatus, 1)
                        .update();
            } else {
                // 用户不在线，把 messageId 存到 Redis
                String redisKey = "offline:msg:" + dto.getReceiverId();

                stringRedisTemplate.opsForList()
                        .rightPush(redisKey, String.valueOf(chatMessage.getId()));

                log.info("接收方用户 [{}] 不在线，消息ID [{}] 已存入 Redis 离线队列",
                        dto.getReceiverId(), chatMessage.getId());
            }

        } catch (Exception e) {
            log.error("处理 WebSocket 消息异常", e);
        }
    }


    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket 发生错误", error);
    }

    /**
     * 发送系统通知 (全局广播用，比如好友申请通知)
     */
    public static void sendSystemMessage(Long receiverId, String msgType, String content) {
        Session session = onlineSessions.get(receiverId);
        if (session != null && session.isOpen()) {
            ChatMessageDTO systemMsg = new ChatMessageDTO();
            systemMsg.setSenderId(0L);
            systemMsg.setReceiverId(receiverId);
            systemMsg.setMsgType(msgType);
            systemMsg.setContent(content);
            systemMsg.setCreateTime(LocalDateTime.now().toString());

            try {
                session.getAsyncRemote().sendText(JSON.toJSONString(systemMsg));
                log.info("向用户 [{}] 推送系统通知成功: {}", receiverId, content);
            } catch (Exception e) {
                log.error("推送系统通知失败", e);
            }
        }
    }


    /**
     * 【新增】：仅向当前发送消息的用户返回错误提示（如被拉黑、被删）
     */
    private void sendSystemTipToSender(Session session, String content) {
        ChatMessageDTO errorMsg = new ChatMessageDTO();
        errorMsg.setSenderId(0L); // 约定 0 为系统消息
        errorMsg.setMsgType("ERROR_TIP");
        errorMsg.setContent(content);
        errorMsg.setCreateTime(LocalDateTime.now().toString());
        session.getAsyncRemote().sendText(JSON.toJSONString(errorMsg));
    }


    /**
     * 判断指定用户当前是否在线
     *
     * @param userId 用户ID
     * @return true 在线，false 离线
     */
    public static boolean isUserOnline(Long userId) {
        Session session = onlineSessions.get(userId);
        return session != null && session.isOpen();
    }


    /**
     * 【新增】：判断是否为通话相关的业务信令
     */
    private boolean isCallMessage(String msgType) {
        return "VIDEO_CALL_REQUEST".equals(msgType) ||
                "VOICE_CALL_REQUEST".equals(msgType) ||
                "CALL_ACCEPT".equals(msgType) ||
                "CALL_REJECT".equals(msgType) ||
                "CALL_CANCEL".equals(msgType) ||
                "CALL_END".equals(msgType);
    }

    /**
     * 【新增】：处理通话信令（入库 + 转发/离线处理）
     */
    private void handleCallMessage(ChatMessageDTO dto, Session session) {
        // 1. 将通话状态存入数据库，保证“有记录可查”
        ChatMessage callRecord = new ChatMessage();
        callRecord.setId(IdWorker.getId());
        callRecord.setSenderId(dto.getSenderId());
        callRecord.setReceiverId(dto.getReceiverId());
        callRecord.setMsgType(dto.getMsgType());

        // 这里的 content 由前端定义，例如：
        // 发起呼叫时 content: {"roomId": 9527}
        // 挂断通话时 content: "通话时长: 15:30"
        callRecord.setContent(dto.getContent());
        callRecord.setStatus(0); // 默认未读，后续你的已读接口会处理它
        callRecord.setCreateTime(LocalDateTime.now());

        // 存入 MySQL
        chatMessageService.save(callRecord);

        // 2. 补全返回给前端的参数，方便前端拿到消息真实 ID
        dto.setMessageId(callRecord.getId());
        dto.setCreateTime(callRecord.getCreateTime().toString());

        // 3. 检查对方是否在线并转发
        Session receiverSession = onlineSessions.get(dto.getReceiverId());
        if (receiverSession != null && receiverSession.isOpen()) {
            // 对方在线，把信令推送过去让对方响铃或接通
            receiverSession.getAsyncRemote().sendText(JSON.toJSONString(dto));
        } else {
            // 对方不在线的情况
            log.info("呼叫接收方 [{}] 不在线", dto.getReceiverId());

            // 如果当前动作是“发起呼叫”，立即给拨打方发送错误提示
            if ("VIDEO_CALL_REQUEST".equals(dto.getMsgType()) || "VOICE_CALL_REQUEST".equals(dto.getMsgType())) {
                sendSystemTipToSender(session, "对方当前不在线，无法接听");

                // (可选进阶优化)：既然对方不在线，你可以立刻再向数据库插入一条状态为 CALL_CANCEL 的记录，
                // 表示这是一通“未接通”的电话，防止对方上线后还看到一直在拨打的 UI 状态。
            }
        }
    }

    /**
     * 用户上线后，推送离线消息
     */
    private void pushRedisOfflineMessages(Long userId, Session session) {
        String redisKey = "offline:msg:" + userId;

        try {
            List<String> messageIdList = stringRedisTemplate.opsForList()
                    .range(redisKey, 0, -1);

            if (messageIdList == null || messageIdList.isEmpty()) {
                log.info("用户 [{}] 没有 Redis 离线消息", userId);
                return;
            }

            for (String messageIdStr : messageIdList) {
                Long messageId = Long.valueOf(messageIdStr);

                ChatMessage msg = chatMessageService.getById(messageId);
                if (msg == null) {
                    continue;
                }

                ChatMessageDTO dto = new ChatMessageDTO();
                dto.setMessageId(msg.getId());
                dto.setSenderId(msg.getSenderId());
                dto.setReceiverId(msg.getReceiverId());
                dto.setMsgType(msg.getMsgType());
                dto.setContent(msg.getContent());

                if (msg.getCreateTime() != null) {
                    dto.setCreateTime(msg.getCreateTime().toString());
                }

                session.getAsyncRemote().sendText(JSON.toJSONString(dto));

                chatMessageService.lambdaUpdate()
                        .eq(ChatMessage::getId, messageId)
                        .set(ChatMessage::getStatus, 1)
                        .update();
            }

            // 全部推送完成后，再删除 Redis 离线队列
            stringRedisTemplate.delete(redisKey);

            log.info("用户 [{}] Redis 离线消息推送完成，共 [{}] 条", userId, messageIdList.size());

        } catch (Exception e) {
            log.error("推送 Redis 离线消息失败，userId={}", userId, e);
        }
    }
}