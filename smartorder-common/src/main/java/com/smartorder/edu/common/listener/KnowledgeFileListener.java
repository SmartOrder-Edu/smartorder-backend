package com.smartorder.edu.common.listener;

import com.smartorder.edu.common.config.RabbitMQConfig;
import com.smartorder.edu.common.mapper.KnowledgeFileJobMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileMapper;
import com.smartorder.edu.common.model.dto.KnowledgeFileDTO;
import com.smartorder.edu.common.model.dto.KnowledgeFileProcessMessage;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.entity.KnowledgeFileJob;
import com.smartorder.edu.common.rag.OutboxService;
import com.smartorder.edu.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.smartorder.edu.common.common.Constants.ACTIVE;
import static com.smartorder.edu.common.common.Constants.PENDING;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeFileListener {

    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeFileJobMapper knowledgeFileJobMapper;
    private final OutboxService outboxService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_FILE_DB_SAVE)
    public void handleFileDbSave(KnowledgeFileDTO msgDTO) {
        try {
            log.info("收到文件入库消息: fileHash={}", msgDTO.getFileHash());
            KnowledgeFile file = saveFileAndCreateJob(msgDTO);
            log.info("文件入库并创建处理任务完成: fileId={}, fileHash={}", file.getId(), file.getFileHash());
        } catch (Exception e) {
            log.error("处理文件入库消息异常: {}", msgDTO, e);
            throw e;
        }
    }

    @Transactional
    public KnowledgeFile saveFileAndCreateJob(KnowledgeFileDTO msg) {
        IdGenerator.setWorkerId(1);
        KnowledgeFile kFile = new KnowledgeFile();
        kFile.setId(IdGenerator.nextId());
        kFile.setUserId(msg.getUserId());
        kFile.setKnowledgeBaseId(msg.getKnowledgeBaseId());
        kFile.setOriginalName(msg.getOriginalName());
        kFile.setStoredName(msg.getStoredName());
        kFile.setFileUrl(msg.getFileUrl());
        kFile.setFileSize(msg.getFileSize());
        kFile.setFileHash(msg.getFileHash());
        fillFileType(kFile, msg.getOriginalName());

        kFile.setParseStatus(PENDING);
        kFile.setChunkStatus(PENDING);
        kFile.setEmbeddingStatus(PENDING);
        kFile.setStatus(ACTIVE);
        kFile.setCreatedAt(LocalDateTime.now());
        kFile.setUpdatedAt(LocalDateTime.now());
        knowledgeFileMapper.insert(kFile);

        KnowledgeFileJob job = new KnowledgeFileJob();
        job.setUserId(kFile.getUserId());
        job.setKnowledgeBaseId(kFile.getKnowledgeBaseId());
        job.setFileId(kFile.getId());
        job.setJobType("parse_chunk_embed");
        job.setStatus(PENDING);
        job.setProgress(0);
        job.setCurrentStep("等待处理");
        job.setRetryCount(0);
        job.setMaxRetryCount(3);
        job.setJobKey("file:" + kFile.getId() + ":parse_chunk_embed:v1");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        knowledgeFileJobMapper.insert(job);


        KnowledgeFileProcessMessage processMessage = new KnowledgeFileProcessMessage(
                job.getId(),
                kFile.getUserId(),
                kFile.getKnowledgeBaseId(),
                kFile.getId()
        );

        outboxService.saveOutbox(
                "knowledge.file.process",
                RabbitMQConfig.KNOWLEDGE_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_FILE_PROCESS,
                processMessage
        );

        return kFile;
    }


    private void fillFileType(KnowledgeFile kFile, String originalName) {
        if (originalName == null || originalName.lastIndexOf(".") <= 0) {
            kFile.setMimeType("application/octet-stream");
            return;
        }
        String extension = originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase();
        kFile.setFileExtension(extension);
        switch (extension) {
            case "txt", "md" -> kFile.setMimeType("text/plain");
            case "pdf" -> kFile.setMimeType("application/pdf");
            case "doc" -> kFile.setMimeType("application/msword");
            case "docx" -> kFile.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xls" -> kFile.setMimeType("application/vnd.ms-excel");
            case "xlsx" -> kFile.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "ppt" -> kFile.setMimeType("application/vnd.ms-powerpoint");
            case "pptx" -> kFile.setMimeType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case "png" -> kFile.setMimeType("image/png");
            case "jpg", "jpeg" -> kFile.setMimeType("image/jpeg");
            case "bmp" -> kFile.setMimeType("image/bmp");
            case "gif" -> kFile.setMimeType("image/gif");
            case "mp3" -> kFile.setMimeType("audio/mpeg");
            case "wav" -> kFile.setMimeType("audio/wav");
            case "mp4" -> kFile.setMimeType("video/mp4");
            case "mkv" -> kFile.setMimeType("video/x-matroska");
            case "webm" -> kFile.setMimeType("video/webm");
            default -> kFile.setMimeType("application/octet-stream");
        }
    }
}
