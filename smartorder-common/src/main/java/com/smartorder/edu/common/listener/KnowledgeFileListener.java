package com.smartorder.edu.common.listener;

import com.smartorder.edu.common.config.RabbitMQConfig;
import com.smartorder.edu.common.domain.dto.KnowledgeFileDTO;
import com.smartorder.edu.common.domain.entity.KnowledgeFile;
import com.smartorder.edu.common.mapper.KnowledgeFileMapper;
import com.smartorder.edu.common.service.IKnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 知识库文件相关 MQ 监听器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeFileListener {

    // 1. 注入 Mapper
    private final KnowledgeFileMapper knowledgeFileMapper;
    /**
     * 监听文件持久化队列，执行数据库入库操作
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_FILE_DB_SAVE)
    public void handleFileDbSave(KnowledgeFileDTO msgDTO) {
        try {
            log.info("收到文件入库消息: fileHash={}", msgDTO.getFileHash());
              executeDbSave(msgDTO);
        } catch (Exception e) {
            log.error("处理文件入库消息异常! message: {}", msgDTO, e);
            // 这里可以根据业务需要决定是否抛出异常（抛出会触发 yaml 中配置的 consumer 重试机制）
            throw e;
        }
    }







    public void executeDbSave(KnowledgeFileDTO msg) {
        KnowledgeFile kFile = new KnowledgeFile();
        kFile.setUserId(msg.getUserId());
        kFile.setKnowledgeBaseId(msg.getKnowledgeBaseId());
        kFile.setOriginalName(msg.getOriginalName());
        kFile.setStoredName(msg.getStoredName());
        kFile.setFileUrl(msg.getFileUrl());
        kFile.setFileSize(msg.getFileSize());
        kFile.setFileHash(msg.getFileHash());

        String originalName = msg.getOriginalName();
        if (originalName != null && originalName.contains(".")) {
            String extension = originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase();
            kFile.setFileExtension(extension);

            switch (extension) {
                case "pdf": kFile.setMimeType("application/pdf"); break;
                case "doc":
                case "docx": kFile.setMimeType("application/msword"); break;
                case "png": kFile.setMimeType("image/png"); break;
                case "jpg":
                case "jpeg": kFile.setMimeType("image/jpeg"); break;
                default: kFile.setMimeType("application/octet-stream");
            }
        }

        kFile.setParseStatus("pending");
        kFile.setChunkStatus("pending");
        kFile.setEmbeddingStatus("pending");
        kFile.setStatus("active");
        kFile.setCreatedAt(LocalDateTime.now());
        kFile.setUpdatedAt(LocalDateTime.now());

        knowledgeFileMapper.insert(kFile);

        log.info("异步文件入库完成: fileId={}, fileHash={}", kFile.getId(), kFile.getFileHash());
    }
}
