package com.smartorder.edu.common.service.impl;

import com.aliyun.oss.model.PartETag;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartorder.edu.common.config.RabbitMQConfig;
import com.smartorder.edu.common.model.dto.ChunkUploadDTO;
import com.smartorder.edu.common.model.dto.KnowledgeFileDTO;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.mapper.KnowledgeFileMapper;
import com.smartorder.edu.common.service.IKnowledgeFileService;
import com.smartorder.edu.common.util.AliOSSUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    // 引入 RabbitTemplate
    private final RabbitTemplate rabbitTemplate;

    private static final long REDIS_EXPIRE_HOURS = 24L;

    @Override
    public ChunkUploadDTO.InitResp initMultipartUpload(ChunkUploadDTO.InitReq req, Long userId) {
        ChunkUploadDTO.InitResp resp = new ChunkUploadDTO.InitResp();

        KnowledgeFile existingFile = this.lambdaQuery()
                .eq(KnowledgeFile::getFileHash, req.getFileHash())
                .eq(KnowledgeFile::getStatus, "active")
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
            // 废弃原先存入 Redis ETag 的操作，减轻 Redis 压力，杜绝不一致风险
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


}