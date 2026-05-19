package com.smartorder.edu.common.listener;

import com.smartorder.edu.common.config.RabbitMQConfig;
import com.smartorder.edu.common.mapper.KnowledgeChunkMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileJobMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileMapper;
import com.smartorder.edu.common.model.dto.KnowledgeFileDTO;
import com.smartorder.edu.common.model.dto.KnowledgeFileProcessMessage;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.entity.KnowledgeFileJob;
import com.smartorder.edu.common.model.rag.RagParseResult;
import com.smartorder.edu.common.rag.*;
import com.smartorder.edu.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.smartorder.edu.common.common.Constants.*;
import static com.smartorder.edu.common.common.Constants.EMBEDDED;
import static com.smartorder.edu.common.common.Constants.FAILED;
import static com.smartorder.edu.common.common.Constants.PROCESSING;
import static com.smartorder.edu.common.common.Constants.RUNNING;
import static com.smartorder.edu.common.common.Constants.SUCCESS;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeFileListener {

    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeFileJobMapper knowledgeFileJobMapper;
    private final OutboxService outboxService;


    private final KnowledgeFileMapper fileMapper;

    private final KnowledgeFileJobMapper jobMapper;

    private final KnowledgeChunkMapper chunkMapper;

    private final RagFileParserService ragFileParserService;

    private final RagPersistService ragPersistService;

    private final VectorIndexService vectorIndexService;

    private final KnowledgeGraphBuildService knowledgeGraphBuildService;


    @RabbitListener(queues = RabbitMQConfig.QUEUE_FILE_DB_SAVE)
    public void handleFileDbSave(KnowledgeFileDTO msgDTO) throws Exception {
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
    public KnowledgeFile saveFileAndCreateJob(KnowledgeFileDTO msg) throws Exception {
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

//        outboxService.saveOutbox(
//                "knowledge.file.process",
//                RabbitMQConfig.KNOWLEDGE_EXCHANGE,
//                RabbitMQConfig.ROUTING_KEY_FILE_PROCESS,
//                processMessage
//        );

        handleFileProcess(processMessage);

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

    public void handleFileProcess(KnowledgeFileProcessMessage msg) throws Exception {
        Long jobId = msg.getJobId();
        try {
            markJobRunning(jobId, "开始处理文件", 5);

            KnowledgeFile file = fileMapper.selectById(msg.getFileId());
            if (file == null) {
                throw new RuntimeException("文件不存在: " + msg.getFileId());
            }


            if (SUCCESS.equals(file.getParseStatus())
                    && SUCCESS.equals(file.getChunkStatus())
                    && SUCCESS.equals(file.getEmbeddingStatus())) {
                log.info("文件已完成处理，跳过: fileId={}", file.getId());
                markJobSuccess(jobId);
                return;
            }


            updateFileStatus(file.getId(), PROCESSING, null, null);
            updateJob(jobId, "解析文件并生成Markdown", 20);
            RagParseResult parseResult = ragFileParserService.parseForRag(file);
            updateFileStatus(file.getId(),SUCCESS,PROCESSING, null);

            updateJob(jobId, "保存文档和分块", 50);
            RagPersistService.PersistResult persistResult = ragPersistService.saveParseResult(parseResult);
            updateFileStatus(file.getId(),SUCCESS, SUCCESS, PROCESSING);

            updateJob(jobId, "构建知识图谱", 60);
            try {
                int nodesCreated = knowledgeGraphBuildService.buildGraphFromChunks(
                        persistResult.chunks(), file.getKnowledgeBaseId(), file.getId());
                log.info("知识图谱构建完成，创建节点数量: {}", nodesCreated);
            } catch (Exception e) {
                log.warn("知识图谱构建失败，继续处理向量索引: {}", e.getMessage());
            }

            updateJob(jobId, "写入向量数据库", 75);
            vectorIndexService.indexChunks(persistResult.chunks());
            markChunksEmbedded(persistResult.chunks());
            updateFileStatus(file.getId(), SUCCESS, SUCCESS, SUCCESS);

            updateJob(jobId, "处理完成", 100);
            markJobSuccess(jobId);
        } catch (Exception e) {
            log.error("文件处理失败: jobId={}, fileId={}", msg.getJobId(), msg.getFileId(), e);
            markJobFailed(jobId, e.getMessage());
            markFileFailed(msg.getFileId(), e.getMessage());
            throw e;
        }
    }

    private void markChunksEmbedded(List<KnowledgeChunk> chunks) {
        for (KnowledgeChunk chunk : chunks) {
            chunk.setEmbeddingStatus(EMBEDDED);
            chunk.setEmbeddingError(null);
            chunk.setUpdatedAt(LocalDateTime.now());
            chunkMapper.updateById(chunk);
        }
    }

    private void markJobRunning(Long jobId, String step, int progress) {
        KnowledgeFileJob job = jobMapper.selectById(jobId);
        if (job == null) return;
        job.setStatus(RUNNING);
        job.setCurrentStep(step);
        job.setProgress(progress);
        job.setStartedAt(job.getStartedAt() == null ? LocalDateTime.now() : job.getStartedAt());
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private void updateJob(Long jobId, String step, int progress) {
        KnowledgeFileJob job = jobMapper.selectById(jobId);
        if (job == null) return;
        job.setCurrentStep(step);
        job.setProgress(progress);
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private void markJobSuccess(Long jobId) {
        KnowledgeFileJob job = jobMapper.selectById(jobId);
        if (job == null) return;
        job.setStatus(SUCCESS);
        job.setProgress(100);
        job.setCurrentStep("处理完成");
        job.setFinishedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private void markJobFailed(Long jobId, String errorMessage) {
        KnowledgeFileJob job = jobMapper.selectById(jobId);
        if (job == null) return;
        job.setStatus(FAILED);
        job.setErrorMessage(errorMessage);
        job.setFinishedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        jobMapper.updateById(job);
    }

    private void updateFileStatus(Long fileId, String parseStatus, String chunkStatus, String embeddingStatus) {
        KnowledgeFile file = fileMapper.selectById(fileId);
        if (file == null) return;
        if (parseStatus != null) file.setParseStatus(parseStatus);
        if (chunkStatus != null) file.setChunkStatus(chunkStatus);
        if (embeddingStatus != null) file.setEmbeddingStatus(embeddingStatus);
        file.setUpdatedAt(LocalDateTime.now());
        fileMapper.updateById(file);
    }

    private void markFileFailed(Long fileId, String errorMessage) {
        KnowledgeFile file = fileMapper.selectById(fileId);
        if (file == null) return;
        if (PROCESSING.equals(file.getParseStatus())) {
            file.setParseStatus(FAILED);
        } else if (PROCESSING.equals(file.getChunkStatus())) {
            file.setChunkStatus(FAILED);
        } else if (PROCESSING.equals(file.getEmbeddingStatus())) {
            file.setEmbeddingStatus(FAILED);
        } else {
            file.setParseStatus(FAILED);
        }
        file.setUpdatedAt(LocalDateTime.now());
        fileMapper.updateById(file);
    }


}
