package com.smartorder.edu.common.listener;

import com.alibaba.fastjson.JSON;
import com.smartorder.edu.common.config.RabbitMQConfig;

import com.smartorder.edu.common.mapper.KnowledgeChunkMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileJobMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileMapper;
import com.smartorder.edu.common.model.dto.KnowledgeFileProcessMessage;
import com.smartorder.edu.common.model.entity.KnowledgeChunk;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.entity.KnowledgeFileJob;
import com.smartorder.edu.common.model.rag.RagParseResult;

import com.smartorder.edu.common.rag.KnowledgeGraphBuildService;
import com.smartorder.edu.common.rag.RagFileParserService;
import com.smartorder.edu.common.rag.RagPersistService;
import com.smartorder.edu.common.rag.VectorIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import static com.smartorder.edu.common.common.Constants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeFileProcessListener {

    private final KnowledgeFileMapper fileMapper;

    private final KnowledgeFileJobMapper jobMapper;

    private final KnowledgeChunkMapper chunkMapper;

    private final RagFileParserService ragFileParserService;

    private final RagPersistService ragPersistService;

    private final VectorIndexService vectorIndexService;

    private final KnowledgeGraphBuildService knowledgeGraphBuildService;


    @RabbitListener(queues = RabbitMQConfig.QUEUE_FILE_PROCESS, containerFactory = "rabbitListenerContainerFactory")
    public void handleFileProcess(String payload) throws Exception {
        KnowledgeFileProcessMessage msg = JSON.parseObject(payload, KnowledgeFileProcessMessage.class);
        handleFileProcess(msg);
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
