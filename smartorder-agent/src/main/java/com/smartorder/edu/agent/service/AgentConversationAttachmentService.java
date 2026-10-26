package com.smartorder.edu.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartorder.edu.agent.exception.AttachmentNotReadyException;
import com.smartorder.edu.agent.mapper.AgentConversationAttachmentMapper;
import com.smartorder.edu.agent.model.dto.AttachmentUploadInitRequest;
import com.smartorder.edu.agent.model.dto.AttachmentUploadMergeRequest;
import com.smartorder.edu.agent.model.entity.AgentConversationAttachment;
import com.smartorder.edu.agent.model.vo.AttachmentStatusVO;
import com.smartorder.edu.agent.model.vo.AttachmentUploadResponse;
import com.smartorder.edu.common.mapper.KnowledgeBaseMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileJobMapper;
import com.smartorder.edu.common.mapper.KnowledgeFileMapper;
import com.smartorder.edu.common.model.dto.KnowledgeFileCreateResult;
import com.smartorder.edu.common.model.dto.KnowledgeFileDTO;
import com.smartorder.edu.common.model.entity.KnowledgeBase;
import com.smartorder.edu.common.model.entity.KnowledgeFile;
import com.smartorder.edu.common.model.entity.KnowledgeFileJob;
import com.smartorder.edu.common.model.rag.AttachmentRagSearchResult;
import com.smartorder.edu.common.rag.VectorIndexService;
import com.smartorder.edu.common.service.KnowledgeFileCreateService;
import com.smartorder.edu.common.util.AliOSSUtils;
import com.smartorder.edu.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.smartorder.edu.common.common.Constants.ACTIVE;
import static com.smartorder.edu.common.common.Constants.DELETED;
import static com.smartorder.edu.common.common.Constants.FAILED;
import static com.smartorder.edu.common.common.Constants.SUCCESS;

@Service
@RequiredArgsConstructor
public class AgentConversationAttachmentService {

    private static final String REDIS_UPLOAD_ID_PREFIX = "agent:attachment:upload:id:";
    private static final String REDIS_OBJECT_KEY_PREFIX = "agent:attachment:upload:key:";
    private static final String TEMP_KB_NAME = "__agent_temp_attachments__";
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int MAX_FILE_COUNT = 5;
    private static final int MAX_CHUNK_CHARS = 900;

    private final AgentConversationAttachmentMapper attachmentMapper;
    private final KnowledgeFileMapper knowledgeFileMapper;
    private final KnowledgeFileJobMapper knowledgeFileJobMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeFileCreateService knowledgeFileCreateService;
    private final VectorIndexService vectorIndexService;
    private final AliOSSUtils aliOSSUtils;
    private final StringRedisTemplate redisTemplate;

    public AttachmentUploadResponse initUpload(AttachmentUploadInitRequest request, Long userId) {
        String conversationId = resolveConversationId(request.getConversationId());
        KnowledgeFile existingFile = findReusableFile(userId, request.getFileHash());
        if (existingFile != null) {
            AgentConversationAttachment attachment = bindAttachment(conversationId, userId, existingFile.getId());
            return toUploadResponse(conversationId, existingFile, findLatestJob(existingFile.getId()), attachment, true);
        }

        String uploadKey = REDIS_UPLOAD_ID_PREFIX + conversationId + ":" + request.getFileHash();
        String uploadId = redisTemplate.opsForValue().get(uploadKey);
        String objectKey = buildObjectKey(conversationId, userId, request.getFileHash(), request.getFileName());

        AttachmentUploadResponse response = new AttachmentUploadResponse();
        response.setIsFinished(false);
        response.setConversationId(conversationId);
        if (uploadId != null) {
            response.setUploadId(uploadId);
            response.setUploadedParts(aliOSSUtils.listUploadedParts(objectKey, uploadId));
        } else {
            uploadId = aliOSSUtils.initiateMultipartUpload(objectKey);
            redisTemplate.opsForValue().set(uploadKey, uploadId, 24, TimeUnit.HOURS);
            redisTemplate.opsForValue().set(REDIS_OBJECT_KEY_PREFIX + uploadId, objectKey, 24, TimeUnit.HOURS);
            response.setUploadId(uploadId);
            response.setUploadedParts(new ArrayList<>());
        }
        response.setStatus("uploading");
        return response;
    }

    public void uploadPart(String uploadId, Integer partNumber, MultipartFile file) {
        String objectKey = redisTemplate.opsForValue().get(REDIS_OBJECT_KEY_PREFIX + uploadId);
        if (objectKey == null) {
            throw new RuntimeException("上传任务已过期或不存在");
        }
        try {
            aliOSSUtils.uploadPart(objectKey, uploadId, partNumber, file.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException("分片上传失败", e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public AttachmentUploadResponse mergeUpload(AttachmentUploadMergeRequest request, Long userId) {
        String conversationId = resolveConversationId(request.getConversationId());
        String objectKey = redisTemplate.opsForValue().get(REDIS_OBJECT_KEY_PREFIX + request.getUploadId());
        if (objectKey == null) {
            throw new RuntimeException("上传任务已过期或不存在");
        }

        var partETags = aliOSSUtils.listAllPartETags(objectKey, request.getUploadId());
        if (partETags.size() < request.getTotalParts()) {
            throw new RuntimeException("分片未全部上传完成，请等待或重试");
        }

        String fileUrl = aliOSSUtils.completeMultipartUpload(objectKey, request.getUploadId(), partETags);
        Long knowledgeBaseId = getOrCreateTempKnowledgeBase(userId);
        String storedName = objectKey.substring(objectKey.lastIndexOf("/") + 1);
        KnowledgeFileCreateResult createResult = knowledgeFileCreateService.createFileAndJob(new KnowledgeFileDTO(
                request.getFileName(),
                storedName,
                fileUrl,
                request.getFileSize(),
                request.getFileHash(),
                knowledgeBaseId,
                userId
        ));

        AgentConversationAttachment attachment = bindAttachment(conversationId, userId, createResult.getFile().getId());
        redisTemplate.delete(REDIS_UPLOAD_ID_PREFIX + conversationId + ":" + request.getFileHash());
        redisTemplate.delete(REDIS_OBJECT_KEY_PREFIX + request.getUploadId());
        return toUploadResponse(conversationId, createResult.getFile(), createResult.getJob(), attachment, true);
    }

    public AttachmentStatusVO getStatus(String conversationId, Long fileId, Long userId) {
        AgentConversationAttachment attachment = getAttachment(conversationId, fileId, userId);
        if (attachment == null) {
            throw new RuntimeException("附件不存在或无权访问");
        }
        KnowledgeFile file = knowledgeFileMapper.selectById(fileId);
        KnowledgeFileJob job = findLatestJob(fileId);
        updateAttachmentStatus(attachment, file, job);
        return toStatusVO(conversationId, file, job, attachment);
    }

    public List<AttachmentStatusVO> listAttachments(String conversationId, Long userId) {
        List<AgentConversationAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<AgentConversationAttachment>()
                        .eq(AgentConversationAttachment::getConversationId, conversationId)
                        .eq(AgentConversationAttachment::getUserId, userId)
                        .ne(AgentConversationAttachment::getStatus, DELETED)
                        .orderByAsc(AgentConversationAttachment::getCreatedAt)
        );
        return attachments.stream()
                .map(attachment -> {
                    KnowledgeFile file = knowledgeFileMapper.selectById(attachment.getFileId());
                    KnowledgeFileJob job = findLatestJob(attachment.getFileId());
                    updateAttachmentStatus(attachment, file, job);
                    return toStatusVO(conversationId, file, job, attachment);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public void deleteAttachment(String conversationId, Long fileId, Long userId) {
        AgentConversationAttachment attachment = getAttachment(conversationId, fileId, userId);
        if (attachment == null) {
            return;
        }
        attachment.setStatus(DELETED);
        attachment.setUpdatedAt(LocalDateTime.now());
        attachmentMapper.updateById(attachment);
    }

    public String buildRagContext(Long userId, String conversationId, List<Long> fileIds, String query) {
        return buildRagContext(userId, conversationId, fileIds, query, DEFAULT_TOP_K);
    }

    public String buildRagContext(Long userId, String conversationId, List<Long> fileIds, String query, int topK) {
        List<Long> readyFileIds = validateReadyFileIds(userId, conversationId, fileIds);
        if (readyFileIds.isEmpty()) {
            return null;
        }

        int safeTopK = Math.max(1, Math.min(topK, MAX_TOP_K));
        List<AttachmentRagSearchResult> results = vectorIndexService.searchByFileIds(
                query, userId, readyFileIds, safeTopK);
        Map<Long, KnowledgeFile> files = knowledgeFileMapper.selectBatchIds(readyFileIds).stream()
                .collect(Collectors.toMap(KnowledgeFile::getId, file -> file));

        if (results.isEmpty()) {
            String names = readyFileIds.stream()
                    .map(files::get)
                    .filter(Objects::nonNull)
                    .map(KnowledgeFile::getOriginalName)
                    .collect(Collectors.joining("、"));
            return "【当前会话附件检索结果】\n已检索附件：" + names
                    + "\n未找到与本轮问题足够相关的附件片段。回答时请明确说明附件中未找到足够依据，不要编造附件内容。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("""
                【当前会话附件检索结果】
                以下内容来自用户本轮指定的附件。回答附件相关问题时，优先依据这些内容。
                如果内容不足，请明确说明“附件中未找到足够依据”。
                回答中如使用附件内容，请用 [1]、[2] 这样的编号标注来源。

                """);
        int index = 1;
        for (AttachmentRagSearchResult result : results) {
            KnowledgeFile file = files.get(result.getFileId());
            builder.append("[")
                    .append(index++)
                    .append("] 文件：")
                    .append(file != null ? file.getOriginalName() : result.getFileId());
            if (result.getPageNumber() != null) {
                builder.append("；页码：").append(result.getPageNumber());
            }
            if (result.getSectionTitle() != null && !result.getSectionTitle().isBlank()) {
                builder.append("；章节：").append(result.getSectionTitle());
            }
            builder.append("\n内容：")
                    .append(truncate(result.getContent(), MAX_CHUNK_CHARS))
                    .append("\n\n");
        }
        return builder.toString();
    }

    private List<Long> validateReadyFileIds(Long userId, String conversationId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = fileIds.stream().distinct().limit(MAX_FILE_COUNT + 1L).toList();
        if (distinctIds.size() > MAX_FILE_COUNT) {
            throw new AttachmentNotReadyException("一次最多只能携带 " + MAX_FILE_COUNT + " 个附件");
        }

        List<Long> readyFileIds = new ArrayList<>();
        for (Long fileId : distinctIds) {
            AgentConversationAttachment attachment = getAttachment(conversationId, fileId, userId);
            if (attachment == null || DELETED.equals(attachment.getStatus())) {
                throw new AttachmentNotReadyException("附件不存在或无权访问");
            }
            KnowledgeFile file = knowledgeFileMapper.selectById(fileId);
            KnowledgeFileJob job = findLatestJob(fileId);
            String status = resolveStatus(file, job);
            updateAttachmentStatus(attachment, file, job);
            if ("ready".equals(status)) {
                readyFileIds.add(fileId);
                continue;
            }
            if (FAILED.equals(status)) {
                throw new AttachmentNotReadyException("附件解析失败，请移除后重新上传");
            }
            throw new AttachmentNotReadyException("附件仍在解析中，请稍后再提问");
        }
        return readyFileIds;
    }

    private AgentConversationAttachment bindAttachment(String conversationId, Long userId, Long fileId) {
        AgentConversationAttachment existing = getAttachment(conversationId, fileId, userId);
        if (existing != null) {
            existing.setStatus("processing");
            existing.setUpdatedAt(LocalDateTime.now());
            attachmentMapper.updateById(existing);
            return existing;
        }

        AgentConversationAttachment attachment = new AgentConversationAttachment();
        attachment.setConversationId(conversationId);
        attachment.setUserId(userId);
        attachment.setFileId(fileId);
        attachment.setStatus("processing");
        attachment.setExpiresAt(LocalDateTime.now().plusDays(7));
        attachment.setCreatedAt(LocalDateTime.now());
        attachment.setUpdatedAt(LocalDateTime.now());
        attachmentMapper.insert(attachment);
        return attachment;
    }

    private AgentConversationAttachment getAttachment(String conversationId, Long fileId, Long userId) {
        return attachmentMapper.selectOne(
                new LambdaQueryWrapper<AgentConversationAttachment>()
                        .eq(AgentConversationAttachment::getConversationId, conversationId)
                        .eq(AgentConversationAttachment::getFileId, fileId)
                        .eq(AgentConversationAttachment::getUserId, userId)
                        .last("LIMIT 1")
        );
    }

    private KnowledgeFile findReusableFile(Long userId, String fileHash) {
        if (fileHash == null || fileHash.isBlank()) {
            return null;
        }
        return knowledgeFileMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeFile>()
                        .eq(KnowledgeFile::getUserId, userId)
                        .eq(KnowledgeFile::getFileHash, fileHash)
                        .eq(KnowledgeFile::getStatus, ACTIVE)
                        .orderByDesc(KnowledgeFile::getCreatedAt)
                        .last("LIMIT 1")
        );
    }

    private KnowledgeFileJob findLatestJob(Long fileId) {
        if (fileId == null) {
            return null;
        }
        return knowledgeFileJobMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeFileJob>()
                        .eq(KnowledgeFileJob::getFileId, fileId)
                        .orderByDesc(KnowledgeFileJob::getCreatedAt)
                        .last("LIMIT 1")
        );
    }

    private void updateAttachmentStatus(AgentConversationAttachment attachment, KnowledgeFile file, KnowledgeFileJob job) {
        if (attachment == null) {
            return;
        }
        String status = resolveStatus(file, job);
        if (!status.equals(attachment.getStatus())) {
            attachment.setStatus(status);
            attachment.setUpdatedAt(LocalDateTime.now());
            attachmentMapper.updateById(attachment);
        }
    }

    private String resolveStatus(KnowledgeFile file, KnowledgeFileJob job) {
        if (file == null || DELETED.equals(file.getStatus())) {
            return DELETED;
        }
        if (FAILED.equals(file.getParseStatus())
                || FAILED.equals(file.getChunkStatus())
                || FAILED.equals(file.getEmbeddingStatus())
                || (job != null && FAILED.equals(job.getStatus()))) {
            return FAILED;
        }
        if (ACTIVE.equals(file.getStatus())
                && SUCCESS.equals(file.getParseStatus())
                && SUCCESS.equals(file.getChunkStatus())
                && SUCCESS.equals(file.getEmbeddingStatus())) {
            return "ready";
        }
        return "processing";
    }

    private AttachmentUploadResponse toUploadResponse(String conversationId, KnowledgeFile file,
                                                      KnowledgeFileJob job, AgentConversationAttachment attachment,
                                                      boolean finished) {
        AttachmentUploadResponse response = new AttachmentUploadResponse();
        response.setIsFinished(finished);
        response.setConversationId(conversationId);
        if (file != null) {
            response.setFileId(file.getId());
            response.setFileName(file.getOriginalName());
            response.setFileUrl(file.getFileUrl());
            response.setParseStatus(file.getParseStatus());
            response.setChunkStatus(file.getChunkStatus());
            response.setEmbeddingStatus(file.getEmbeddingStatus());
        }
        if (job != null) {
            response.setJobId(job.getId());
            response.setProgress(job.getProgress());
            response.setCurrentStep(job.getCurrentStep());
            response.setErrorMessage(job.getErrorMessage());
        }
        if (attachment != null) {
            updateAttachmentStatus(attachment, file, job);
            response.setStatus(attachment.getStatus());
        }
        return response;
    }

    private AttachmentStatusVO toStatusVO(String conversationId, KnowledgeFile file,
                                          KnowledgeFileJob job, AgentConversationAttachment attachment) {
        if (file == null) {
            return null;
        }
        AttachmentStatusVO vo = new AttachmentStatusVO();
        vo.setConversationId(conversationId);
        vo.setFileId(file.getId());
        vo.setFileName(file.getOriginalName());
        vo.setFileSize(file.getFileSize());
        vo.setFileExtension(file.getFileExtension());
        vo.setStatus(attachment != null ? attachment.getStatus() : resolveStatus(file, job));
        vo.setParseStatus(file.getParseStatus());
        vo.setChunkStatus(file.getChunkStatus());
        vo.setEmbeddingStatus(file.getEmbeddingStatus());
        if (job != null) {
            vo.setJobId(job.getId());
            vo.setProgress(job.getProgress());
            vo.setCurrentStep(job.getCurrentStep());
            vo.setErrorMessage(job.getErrorMessage());
        }
        return vo;
    }

    private Long getOrCreateTempKnowledgeBase(Long userId) {
        KnowledgeBase existing = knowledgeBaseMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getUserId, userId)
                        .eq(KnowledgeBase::getName, TEMP_KB_NAME)
                        .ne(KnowledgeBase::getStatus, DELETED)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            return existing.getId();
        }

        IdGenerator.setWorkerId(1);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(IdGenerator.nextId());
        kb.setUserId(userId);
        kb.setName(TEMP_KB_NAME);
        kb.setDescription("Agent 会话临时附件知识库");
        kb.setCategory("agent_attachment");
        kb.setEmbeddingModel("text-embedding-3-small");
        kb.setChunkStrategy("recursive");
        kb.setRetrievalTopK(DEFAULT_TOP_K);
        kb.setScoreThreshold(new BigDecimal("0.70"));
        kb.setFileCount(0);
        kb.setDocumentCount(0);
        kb.setChunkCount(0);
        kb.setTotalSize(0L);
        kb.setStatus("system");
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        kb.setLastUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.insert(kb);
        return kb.getId();
    }

    private String resolveConversationId(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }
        return UUID.randomUUID().toString();
    }

    private String buildObjectKey(String conversationId, Long userId, String fileHash, String fileName) {
        return "agent-attachments/" + userId + "/" + conversationId + "/" + fileHash + "-" + fileName;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
