package com.smartorder.edu.student.listener;

import com.smartorder.edu.student.domain.entity.FileResource;
import com.smartorder.edu.student.mapper.FileResourceMapper;
import com.smartorder.edu.student.service.IUserFileQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

import static com.smartorder.edu.common.config.YunPanMQConfig.*;

/**
 * 云盘MQ监听器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YunPanListener {
    private final IUserFileQuotaService userFileQuotaService;
    private final FileResourceMapper fileResourceMapper;

    @RabbitListener(bindings = @QueueBinding(
            exchange = @Exchange(name = MQ_YUN_PAN_EXCHANGE, type = ExchangeTypes.DIRECT, durable = "true"),
            value = @Queue(name = MQ_QUEUE_FILE_DB_QUOTE, durable = "true"),
            key = {MQ_ROUTING_KEY_FILE_QUOTE}
    ))
    public void handleUserRegistered(Object message) {
        if (!(message instanceof Long userId)) {
            log.warn("用户注册消息格式不正确，无法处理: {}", message);
            return;
        }
        log.info("为新用户 {} 初始化云盘配额", userId);
        userFileQuotaService.initQuota(userId);
    }

    @RabbitListener(bindings = @QueueBinding(
            exchange = @Exchange(name = MQ_YUN_PAN_EXCHANGE, type = ExchangeTypes.DIRECT, durable = "true"),
            value = @Queue(name = MQ_QUEUE_FILE_SAVE, durable = "true"),
            key = {MQ_ROUTING_KEY_FILE_SAVE}
    ))
    @SuppressWarnings("unchecked")
    public void saveFileToDB(Object message) {
        if (!(message instanceof Map<?, ?> map)) {
            log.warn("文件保存消息格式不正确，无法处理: {}", message);
            return;
        }
        try {
            Long userId = ((Number) map.get("userId")).longValue();
            Long fileId = ((Number) map.get("fileId")).longValue();
            Map<String, Object> dto = (Map<String, Object>) map.get("initFileUploadDTO");

            String fileName = (String) dto.get("fileName");
            Long fileSize = dto.get("fileSize") != null ? ((Number) dto.get("fileSize")).longValue() : null;
            String fileHash = (String) dto.get("fileHash");
            Long targetFolderId = ((Number) dto.get("targetFolderId")).longValue();

            int dotIndex = fileName.lastIndexOf('.');
            FileResource file = new FileResource();
            file.setId(fileId);
            file.setFolderId(targetFolderId);
            file.setUserId(userId);
            file.setFileName(fileName);
            file.setOriginalFileName(fileName);
            file.setFileHash(fileHash);
            file.setFileType(dotIndex >= 0 ? fileName.substring(dotIndex + 1) : null);
            file.setFileSize(fileSize);
            file.setSource(1);
            file.setUploadStatus(1);
            file.setParseStatus(0);
            file.setCreateTime(LocalDateTime.now());
            file.setUpdateTime(LocalDateTime.now());

            // 从已有文件中复制OSS路径
            Map<String, Object> existingFile = (Map<String, Object>) map.get("fileResource");
            if (existingFile != null) {
                file.setFileUrl((String) existingFile.get("fileUrl"));
            }

            fileResourceMapper.insert(file);
            log.info("文件去重保存成功，fileId: {}, userId: {}", fileId, userId);

            // 更新用户已用空间
            if (fileSize != null && fileSize > 0) {
                userFileQuotaService.addUserSpace(userId, fileSize);
            }
        } catch (Exception e) {
            log.error("文件保存到数据库失败, message: {}", message, e);
        }
    }

}
