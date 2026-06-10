package com.smartorder.edu.common.service;

import com.smartorder.edu.common.config.RabbitMQConfig;
import com.smartorder.edu.common.mapper.KnowledgeFileJobMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileMapper;
import com.smartorder.edu.common.model.dto.KnowledgeFileCreateResult;
import com.smartorder.edu.common.model.dto.KnowledgeFileDTO;
import com.smartorder.edu.common.model.dto.KnowledgeFileProcessMessage;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.entity.KnowledgeFileJob;
import com.smartorder.edu.common.rag.OutboxService;
import com.smartorder.edu.common.service.impl.KnowledgeBaseServiceImpl;
import com.smartorder.edu.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.smartorder.edu.common.common.Constants.ACTIVE;
import static com.smartorder.edu.common.common.Constants.PENDING;

@Service
@RequiredArgsConstructor
public class KnowledgeFileCreateService {

    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeFileJobMapper knowledgeFileJobMapper;
    private final OutboxService outboxService;
    private final KnowledgeBaseServiceImpl knowledgeBaseService;

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeFileCreateResult createFileAndJob(KnowledgeFileDTO msg) {
        IdGenerator.setWorkerId(1);

        KnowledgeFile file = new KnowledgeFile();
        file.setId(IdGenerator.nextId());
        file.setUserId(msg.getUserId());
        file.setKnowledgeBaseId(msg.getKnowledgeBaseId());
        file.setOriginalName(msg.getOriginalName());
        file.setStoredName(msg.getStoredName());
        file.setFileUrl(msg.getFileUrl());
        file.setFileSize(msg.getFileSize());
        file.setFileHash(msg.getFileHash());
        fillFileType(file, msg.getOriginalName());
        file.setParseStatus(PENDING);
        file.setChunkStatus(PENDING);
        file.setEmbeddingStatus(PENDING);
        file.setStatus(ACTIVE);
        file.setCreatedAt(LocalDateTime.now());
        file.setUpdatedAt(LocalDateTime.now());
        knowledgeFileMapper.insert(file);

        KnowledgeFileJob job = new KnowledgeFileJob();
        job.setUserId(file.getUserId());
        job.setKnowledgeBaseId(file.getKnowledgeBaseId());
        job.setFileId(file.getId());
        job.setJobType("parse_chunk_embed");
        job.setStatus(PENDING);
        job.setProgress(0);
        job.setCurrentStep("等待处理");
        job.setRetryCount(0);
        job.setMaxRetryCount(3);
        job.setJobKey("file:" + file.getId() + ":parse_chunk_embed:v1");
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        knowledgeFileJobMapper.insert(job);

        KnowledgeFileProcessMessage processMessage = new KnowledgeFileProcessMessage(
                job.getId(),
                file.getUserId(),
                file.getKnowledgeBaseId(),
                file.getId()
        );
        outboxService.saveOutbox(
                "knowledge.file.process",
                RabbitMQConfig.KNOWLEDGE_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_FILE_PROCESS,
                processMessage
        );

        knowledgeBaseService.incrementFileStats(file.getKnowledgeBaseId(), file.getFileSize());
        return new KnowledgeFileCreateResult(file, job);
    }

    private void fillFileType(KnowledgeFile file, String originalName) {
        if (originalName == null || originalName.lastIndexOf(".") <= 0) {
            file.setMimeType("application/octet-stream");
            return;
        }
        String extension = originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase();
        file.setFileExtension(extension);
        switch (extension) {
            case "txt", "md" -> file.setMimeType("text/plain");
            case "pdf" -> file.setMimeType("application/pdf");
            case "doc" -> file.setMimeType("application/msword");
            case "docx" -> file.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "xls" -> file.setMimeType("application/vnd.ms-excel");
            case "xlsx" -> file.setMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "ppt" -> file.setMimeType("application/vnd.ms-powerpoint");
            case "pptx" -> file.setMimeType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case "png" -> file.setMimeType("image/png");
            case "jpg", "jpeg" -> file.setMimeType("image/jpeg");
            case "bmp" -> file.setMimeType("image/bmp");
            case "gif" -> file.setMimeType("image/gif");
            case "mp3" -> file.setMimeType("audio/mpeg");
            case "wav" -> file.setMimeType("audio/wav");
            case "mp4" -> file.setMimeType("video/mp4");
            case "mkv" -> file.setMimeType("video/x-matroska");
            case "webm" -> file.setMimeType("video/webm");
            default -> file.setMimeType("application/octet-stream");
        }
    }
}
