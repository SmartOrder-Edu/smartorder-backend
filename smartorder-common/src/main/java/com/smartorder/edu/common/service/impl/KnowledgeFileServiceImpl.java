package com.smartorder.edu.common.service.impl;

import com.aliyun.oss.model.PartETag;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartorder.edu.common.config .RabbitMQConfig;
import com.smartorder.edu.common.mapper.*;
import com.smartorder.edu.common.model.dto.ChunkUploadDTO;
import com.smartorder.edu.common.model.dto.KnowledgeFileDTO;
import com.smartorder.edu.common.model.entity.*;
import com.smartorder.edu.common.rag.VectorIndexService;
import com.smartorder.edu.common.service.IKnowledgeFileService;
import com.smartorder.edu.common.service.INeo4jKnowledgeGraphService;
import com.smartorder.edu.common.util.AliOSSUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.smartorder.edu.common.common.Constants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeFileServiceImpl extends ServiceImpl<KnowledgeFileMapper, KnowledgeFile> implements IKnowledgeFileService {

    private final AliOSSUtils aliOSSUtils;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeFileJobMapper knowledgeFileJobMapper;
    private final RagReferenceMapper ragReferenceMapper;
    private final INeo4jKnowledgeGraphService neo4jKnowledgeGraphService;
    private final VectorIndexService vectorIndexService;
    private final KnowledgeBaseServiceImpl knowledgeBaseService;

    private static final long REDIS_EXPIRE_HOURS = 24L;

    @Override
    public ChunkUploadDTO.InitResp initMultipartUpload(ChunkUploadDTO.InitReq req, Long userId) {
        ChunkUploadDTO.InitResp resp = new ChunkUploadDTO.InitResp();

        KnowledgeFile existingFile = this.lambdaQuery()
                .eq(KnowledgeFile::getFileHash, req.getFileHash())
                .eq(KnowledgeFile::getStatus, "active")
                .eq(KnowledgeFile::getUserId, userId)  // 【关键新增：过滤当前用户】
                .last("LIMIT 1")
                .one();

        if (existingFile != null) {
            // 【修改点】：调用异步发送方法
            sendAsyncSaveMessage(req.getFileName(), existingFile.getStoredName(), existingFile.getFileUrl(),
                    req.getFileSize(), req.getFileHash(), req.getKnowledgeBaseId(), userId);

            resp.setIsFinished(true);
            resp.setFileUrl(existingFile.getFileUrl());
            return resp;
        }

        String uploadIdKey = REDIS_UPLOAD_ID_PREFIX + req.getFileHash();
        String uploadId = redisTemplate.opsForValue().get(uploadIdKey);
        String objectKey = "knowledge-base/" + req.getKnowledgeBaseId() + "/" + req.getFileHash() + "-" + req.getFileName();

        resp.setIsFinished(false);

        if (uploadId != null) {
            List<Integer> uploadedParts = aliOSSUtils.listUploadedParts(objectKey, uploadId);
            resp.setUploadId(uploadId);
            resp.setUploadedParts(uploadedParts);
        } else {
            uploadId = aliOSSUtils.initiateMultipartUpload(objectKey);
            resp.setUploadId(uploadId);
            resp.setUploadedParts(new ArrayList<>());

            redisTemplate.opsForValue().set(uploadIdKey, uploadId, REDIS_EXPIRE_HOURS, TimeUnit.HOURS);
            redisTemplate.opsForValue().set(REDIS_OBJECT_KEY_PREFIX + uploadId, objectKey, REDIS_EXPIRE_HOURS, TimeUnit.HOURS);
        }

        return resp;
    }

    @Override
    public void uploadPart(String uploadId, Integer partNumber, MultipartFile file) {
        // 1. 从 Redis 获取 ObjectKey
        String objectKey = redisTemplate.opsForValue().get(REDIS_OBJECT_KEY_PREFIX + uploadId);
        if (objectKey == null) {
            throw new RuntimeException("上传任务已过期或不存在");
        }

        try {
            // 2. 直接调用 OSS 上传分片即可！
            aliOSSUtils.uploadPart(objectKey, uploadId, partNumber, file.getInputStream());
        } catch (IOException e) {
            log.error("分片 {} 上传失败, uploadId: {}", partNumber, uploadId, e);
            throw new RuntimeException("分片上传失败");
        }
    }

    @Override
    public String completeMultipartUpload(ChunkUploadDTO.MergeReq req, Long userId) {
        String uploadId = req.getUploadId();
        String objectKey = redisTemplate.opsForValue().get(REDIS_OBJECT_KEY_PREFIX + uploadId);

        if (objectKey == null) {
            throw new RuntimeException("上传任务已过期或不存在");
        }

        // 1. 【核心优化】直接去阿里云 OSS 查询该任务所有已成功上传的分片！
        List<PartETag> partETags = aliOSSUtils.listAllPartETags(objectKey, uploadId);

        // 2. 校验分片数量是否齐全
        if (partETags.size() < req.getTotalParts()) {
            throw new RuntimeException("分片未全部上传完成，请等待或重试");
        }

        // 3. 调用 OSS 合并分片
        String fileUrl = aliOSSUtils.completeMultipartUpload(objectKey, uploadId, partETags);

        // 【修改点】：调用异步发送方法
        sendAsyncSaveMessage(req.getFileName(), objectKey.substring(objectKey.lastIndexOf("/") + 1),
                fileUrl, req.getFileSize(), req.getFileHash(), req.getKnowledgeBaseId(), userId);

        // 5. 异步清理 Redis 缓存 (现在只需要清理这两个 Key)
        redisTemplate.delete(Arrays.asList(
                REDIS_UPLOAD_ID_PREFIX + req.getFileHash(),
                REDIS_OBJECT_KEY_PREFIX + uploadId
        ));

        return fileUrl;
    }

    /**
     * 发送 MQ 消息，实现异步入库
     */
    private void sendAsyncSaveMessage(String originalName, String storedName, String fileUrl,
                                      Long fileSize, String fileHash, Long kbId, Long userId) {
        KnowledgeFileDTO msg = new KnowledgeFileDTO(
                originalName, storedName, fileUrl, fileSize, fileHash, kbId, userId
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.KNOWLEDGE_EXCHANGE, RabbitMQConfig.ROUTING_KEY_FILE_DB_SAVE, msg);
        log.info("异步文件入库消息已发送: fileHash={}", fileHash);
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledgeFile(Long fileId, Long userId) {
        // 1. 校验文件是否存在，且该文件属于当前用户
        KnowledgeFile existingFile = this.lambdaQuery()
                .eq(KnowledgeFile::getId, fileId)
                .eq(KnowledgeFile::getUserId, userId)
                .one();

        if (existingFile == null) {
            throw new RuntimeException("文件不存在或您无权删除该文件");
        }

        log.info("开始删除文件，fileId={}, userId={}", fileId, userId);

        try {
            // 2. 删除 Neo4j 中的图节点
            try {
                neo4jKnowledgeGraphService.deleteNodesByFileId(fileId);
            } catch (Exception e) {
                log.warn("删除 Neo4j 节点失败，继续执行", e);
            }

            // 3. 删除 Milvus 中的向量
            try {
                vectorIndexService.deleteByFileId(fileId);
            } catch (Exception e) {
                log.warn("删除 Milvus 向量失败，继续执行", e);
            }

            // 4. 查询该文件关联的 chunk 数量，用于更新知识库统计
            long chunkCount = knowledgeChunkMapper.selectCount(
                    new LambdaQueryWrapper<KnowledgeChunk>()
                            .eq(KnowledgeChunk::getFileId, fileId)
            );

            // 5. 删除 MySQL 中的子表数据（按依赖顺序）
            // 5.1 删除 rag_references
            ragReferenceMapper.delete(
                    new LambdaQueryWrapper<RagReference>()
                            .eq(RagReference::getFileId, fileId)
            );

            // 5.2 删除 knowledge_chunks
            knowledgeChunkMapper.delete(
                    new LambdaQueryWrapper<KnowledgeChunk>()
                            .eq(KnowledgeChunk::getFileId, fileId)
            );

            // 5.3 删除 knowledge_documents
            knowledgeDocumentMapper.delete(
                    new LambdaQueryWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getFileId, fileId)
            );

            // 5.4 删除 knowledge_file_jobs
            knowledgeFileJobMapper.delete(
                    new LambdaQueryWrapper<KnowledgeFileJob>()
                            .eq(KnowledgeFileJob::getFileId, fileId)
            );

            // 6. 软删除 knowledge_files（更新状态为 deleted）
            existingFile.setStatus("deleted");
            existingFile.setUpdatedAt(LocalDateTime.now());
            this.updateById(existingFile);

            // 7. 更新知识库统计
            knowledgeBaseService.decrementFileStats(
                    existingFile.getKnowledgeBaseId(),
                    existingFile.getFileSize(),
                    (int) chunkCount
            );

            log.info("文件删除成功，fileId={}", fileId);

        } catch (Exception e) {
            log.error("删除文件失败，fileId={}", fileId, e);
            throw new RuntimeException("删除文件失败: " + e.getMessage(), e);
        }
    }
}