package com.smartorder.edu.common.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import com.smartorder.edu.common.domain.entity.ChatMessage;
import com.smartorder.edu.common.domain.vo.Result;
import com.smartorder.edu.common.service.IChatMessageService;
import com.smartorder.edu.common.util.AliOSSUtils;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

import static com.smartorder.edu.common.constant.ResultCode.UNSUPPORTED_FILE_TYPE;
import static com.smartorder.edu.common.constant.ResultCode.UPLOAD_TASK_NOT_FOUND;


@RestController
@RequestMapping("/user/chat")
public class ChatMessageController {

    @Autowired
    private IChatMessageService chatMessageService;

    @Autowired
    private AliOSSUtils aliOSSUtils;

    /**
     * 1. 获取最近聊天会话列表
     */
    @GetMapping("/conversations")
    public Result<List<ChatMessage>> getConversations() {
        Claims claims = ThreadLocalUtils.get();
        Long teacherId = claims.get("userId", Long.class);

        List<ChatMessage> list = chatMessageService.getConversations(teacherId);
        return Result.success(list, "获取会话列表成功");
    }

    /**
     * 2. 分页获取具体的双人聊天历史记录
     */
    @GetMapping("/history")
    public Result<Page<ChatMessage>> getChatHistory(
            @RequestParam("targetTeacherId") Long targetTeacherId,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {

        Claims claims = ThreadLocalUtils.get();
        Long currentTeacherId = claims.get("userId", Long.class);

        Page<ChatMessage> historyPage = chatMessageService.getChatHistory(currentTeacherId, targetTeacherId, page, size);
        return Result.success(historyPage, "获取历史记录成功");
    }

    /**
     * 3. 标记消息为已读
     */
    @PutMapping("/read")
    public Result<String> markAsRead(@RequestParam("senderId") Long senderId) {
        Claims claims = ThreadLocalUtils.get();
        Long receiverId = claims.get("userId", Long.class);

        chatMessageService.markAsRead(senderId, receiverId);
        return Result.success("消息已标为已读");
    }

    /**
     * 4. 撤回消息
     */
    @PutMapping("/recall/{messageId}")
    public Result<String> recallMessage(@PathVariable("messageId") Long messageId) {
        Claims claims = ThreadLocalUtils.get();
        Long teacherId = claims.get("userId", Long.class);

        // 如果抛出异常，建议由全局异常处理器(@RestControllerAdvice)统一捕获并返回错误Result
        chatMessageService.recallMessage(messageId, teacherId);
        return Result.success("撤回成功");
    }


    /**
     * 上传聊天文件 (图片、语音、文档)
     */
    @PostMapping("/upload")
    public Result<String> uploadChatFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("msgType") String msgType) {

        try {
            // 1. 获取当前用户 ID (用于在 OSS 中按用户分目录存储，方便管理)
            Claims claims = ThreadLocalUtils.get();
            Long teacherId = claims.get("userId", Long.class);

            // 2. 根据消息类型决定 OSS 中的存储目录
            String directory;
            switch (msgType.toUpperCase()) {
                case "IMAGE":
                    directory = "chat/images/" + teacherId + "/";
                    break;
                case "VOICE":
                    directory = "chat/voice/" + teacherId + "/";
                    break;
                case "FILE":
                    directory = "chat/files/" + teacherId + "/";
                    break;
                default:
                    return Result.fail( UNSUPPORTED_FILE_TYPE, "不支持的消息类型");
            }

            // 3. 调用你写好的工具类上传到 OSS
            String fileUrl = aliOSSUtils.upload(file, directory);

            // 4. 返回 OSS 的访问 URL 给前端
            return Result.success(fileUrl, "上传成功");

        } catch (IllegalArgumentException e) {
            return Result.fail( UNSUPPORTED_FILE_TYPE, e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            return Result.fail(UPLOAD_TASK_NOT_FOUND, "文件上传到OSS失败");
        }
    }
}
