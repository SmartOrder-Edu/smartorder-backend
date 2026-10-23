package com.smartorder.edu.student.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.model.dto.FileSaveMessage;
import com.smartorder.edu.common.util.AliOSSUtils;
import com.smartorder.edu.student.domain.dto.InitFileUploadDTO;
import com.smartorder.edu.student.domain.entity.FileResource;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.mapper.FileResourceMapper;
import com.smartorder.edu.student.service.IFileResourceService;
import com.smartorder.edu.student.service.IUserFileQuotaService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.smartorder.edu.common.config.YunPanMQConfig.MQ_ROUTING_KEY_FILE_SAVE;
import static com.smartorder.edu.common.config.YunPanMQConfig.MQ_YUN_PAN_EXCHANGE;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileResourceServiceImpl implements IFileResourceService {

    private final FileResourceMapper fileResourceMapper;
    private final AliOSSUtils aliOSSUtils;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final IUserFileQuotaService userFileQuotaService;

    @Override
    public Page<FileResourceVO> getFilesByFolder(Long folderId, Long userId, Integer pageNum, Integer pageSize) {
        QueryWrapper<FileResource> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (folderId != null) {
            wrapper.eq("folder_id", folderId);
        }
        wrapper.orderByDesc("create_time");

        Page<FileResource> page = new Page<>(pageNum, pageSize);
        Page<FileResource> result = fileResourceMapper.selectPage(page, wrapper);

        List<FileResourceVO> vos = result.getRecords().stream().map(r -> {
            FileResourceVO vo = new FileResourceVO();
            BeanUtils.copyProperties(r, vo);
            return vo;
        }).collect(Collectors.toList());

        Page<FileResourceVO> voPage = new Page<>(pageNum, pageSize, result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public FileResourceVO getFileByIdAndUserId(Long id,Long userId) {
        LambdaQueryWrapper<FileResource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileResource::getId, id)
                .eq(FileResource::getUserId, userId);
        FileResource file = fileResourceMapper.selectOne(wrapper);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        FileResourceVO vo = new FileResourceVO();
        BeanUtils.copyProperties(file, vo);
        return vo;
    }

    @Override
    @Transactional
    public void deleteFile(Long id, Long userId) {
        LambdaQueryWrapper<FileResource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileResource::getId, id)
                .eq(FileResource::getUserId, userId);
        FileResource file = fileResourceMapper.selectOne(wrapper);
        if (file == null) {
            throw new BusinessException("文件不存在或无权删除该文件");
        }
        // 仅当没有其他记录引用同一OSS文件时，才删除OSS上的文件（秒传场景下多个记录共享同一文件）
        if (file.getFileUrl()!=null&&!file.getFileUrl().isEmpty()) {
            LambdaQueryWrapper<FileResource> refWrapper = new LambdaQueryWrapper<>();
            refWrapper.eq(FileResource::getFileUrl, file.getFileUrl())
                    .ne(FileResource::getId, id);
            Long refCount = fileResourceMapper.selectCount(refWrapper);
            if (refCount == 0) {
                aliOSSUtils.deleteFile(file.getFileUrl());
            }
        }

        // 删除数据库记录
        fileResourceMapper.deleteById(id);

        // 释放已用空间
        if (file.getFileSize() != null && file.getFileSize() > 0) {
            userFileQuotaService.reduceUserSpace(userId, file.getFileSize());
        }
    }

    @Override
    public void updateParseStatus(Long id, Integer parseStatus) {
        FileResource file = fileResourceMapper.selectById(id);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        file.setParseStatus(parseStatus);
        file.setUpdateTime(LocalDateTime.now());
        fileResourceMapper.updateById(file);
    }

    @Override
    public List<FileResourceVO> getFilesByParseStatus(Integer parseStatus) {
        QueryWrapper<FileResource> wrapper = new QueryWrapper<>();
        wrapper.eq("parse_status", parseStatus);
        return fileResourceMapper.selectList(wrapper).stream().map(r -> {
            FileResourceVO vo = new FileResourceVO();
            BeanUtils.copyProperties(r, vo);
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> initMultipartUpload(InitFileUploadDTO dto, Long userId) {
        String fileName = dto.getFileName();
        Long fileSize = dto.getFileSize();
        String fileHash = dto.getFileHash();
        Long targetFolderId = dto.getTargetFolderId();
        String fileId = IdUtil.getSnowflakeNextIdStr();

        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;

        String objectKey = "yun/" + baseName + fileId + fileName.substring(fileName.lastIndexOf('.'));
        // 检查该用户是否有已有的上传任务
        String uploadKey = "yun:upload:task:" + fileHash;
        String uploadTaskJson = redisTemplate.opsForValue().get(uploadKey);
        UploadTask uploadTask = uploadTaskJson != null ? fromJson(uploadTaskJson, UploadTask.class) : null;
        if(uploadTask!=null){
            log.info("发现已有的上传任务，继续使用该任务进行上传，objectKey:{}",objectKey);
            Map<String,Object> result = Map.of(
                    "uploadId", uploadTask.uploadId,
                    "uploadedChunksParts", uploadTask.uploadedParts,
                    "fileId", fileId.toString()
            );
            return result;
        }
        // 检查是否上传过类似的文件（根据文件Hash值），如果有则直接使用之前的上传ID和已上传分片列表，继续上传
        FileResource fileResource = fileResourceMapper.selectOne(
                new LambdaQueryWrapper<FileResource>()
                        .eq(FileResource::getFileHash, fileHash)
                        .eq(FileResource::getUploadStatus, 1)
        );
        if(fileResource!=null){
            sendAsyncSaveData(dto,fileResource,fileId,userId);
            // Map.of 的 value 不允许为 null，秒传分支（uploadId/分片列表均为空）必须用 HashMap，
            // 否则这里会在消息发出之后抛 NPE，接口 100% 500 而消息已经投递出去
            Map<String, Object> finished = new HashMap<>(4);
            finished.put("uploadId", null);
            finished.put("uploadedChunksParts", null);
            finished.put("fileId", fileId);
            finished.put("isFinished", true);
            return finished;
        }
        // 分片上传唯一标识(凭证)
        String uploadId = aliOSSUtils.initiateMultipartUpload(objectKey);
        // 查询已上传分片列表(断点传续)
        List<Integer> listUploadedParts = aliOSSUtils.listUploadedParts(objectKey, uploadId);

        UploadTask task = new UploadTask(fileHash, objectKey, uploadId, listUploadedParts, new ArrayList<>());

        log.info("task:{}",task);
        redisTemplate.opsForValue().set(uploadKey,toJson(task),30, TimeUnit.MINUTES);

        // 存储文件元信息，等待分片上传完成后更新URL和状态
        FileResource file = new FileResource();
        file.setId(Long.valueOf(fileId));
        file.setFolderId(targetFolderId);
        file.setUserId(userId);
        file.setFileName(fileName);
        file.setOriginalFileName(fileName);
        file.setFileUrl(objectKey);
        file.setFileHash(fileHash);
        file.setFileType(fileName.substring(fileName.lastIndexOf('.') + 1));
        file.setFileSize(fileSize);
        file.setSource(1);
        file.setUploadStatus(0);
        file.setParseStatus(0);
        fileResourceMapper.insert(file);

        log.info("文件记录已经创建，并且已经检查已上传的文件,文件ID：{}，对象Key：{}", fileId, objectKey);
        Map<String,Object> result = Map.of(
                "uploadId", uploadId,
                "uploadedChunksParts", listUploadedParts,
                "fileId", fileId.toString(),
                "isFinished", false
        );
        return result;
    }

    /**
     * 秒传分支：发送"异步落库"消息。
     * <p>
     * 消息体使用强类型 {@link FileSaveMessage} 而非 Map：
     * Map 的 key 在编译期无约束，且 {@code Map.of} 不接受 null 值；
     * 同时不把 {@code FileResource} 整行实体塞进消息（其中 summaryContent 可能是几千字的 AI 摘要），
     * 消费端真正需要的只有被复用文件的 objectKey。
     * <p>
     * 挂 {@link CorrelationData} 是为了拿到 Broker 的发布确认：
     * exchange 不存在 / 队列不可路由时，confirm 返回 nack，避免"用户看到秒传成功但消息已丢"。
     */
    private void sendAsyncSaveData(InitFileUploadDTO dto, FileResource src, String fileId, Long userId) {
        FileSaveMessage msg = new FileSaveMessage(
                fileId,
                userId,
                dto.getTargetFolderId() == null ? 0L : dto.getTargetFolderId(), // null 归一化为根目录
                dto.getFileName(),
                src.getFileSize(),   // 以库中真实大小为准，不信任前端入参
                src.getFileHash(),
                src.getFileUrl());   // 秒传记录与被复用文件共享同一份 OSS 对象

        log.info("发送秒传入库消息，fileId: {}, fileHash: {}, objectKey: {}",
                fileId, msg.getFileHash(), msg.getObjectKey());

        CorrelationData correlationData = new CorrelationData(fileId);
        correlationData.getFuture().whenComplete((confirm, ex) -> {
            if (ex != null) {
                log.error("秒传入库消息发送异常，fileId: {}", fileId, ex);
            } else if (confirm != null && !confirm.isAck()) {
                log.error("秒传入库消息未能抵达 Broker，fileId: {}, reason: {}", fileId, confirm.getReason());
            }
        });

        rabbitTemplate.convertAndSend(MQ_YUN_PAN_EXCHANGE, MQ_ROUTING_KEY_FILE_SAVE, msg, correlationData);
    }

    /**
     * 秒传文件入库（MQ 消费者调用）。
     * <p>
     * 幂等与事务：文件记录 insert 与用户空间累加在同一事务内，任一失败整体回滚；
     * 重复投递时因主键（fileId 由生产者生成，重投不变）冲突而回滚，
     * 天然保证容量不会被重复扣减。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveQuickUploadFile(FileSaveMessage msg) {
        Long fileId = Long.parseLong(msg.getFileId());

        if (fileResourceMapper.selectById(fileId) != null) {
            log.info("秒传文件记录已存在，幂等跳过，fileId: {}", fileId);
            return;
        }
        // 没有 objectKey 的秒传记录无法预览/下载，宁可失败重试也不要写入脏数据
        if (!StringUtils.hasText(msg.getObjectKey())) {
            throw new BusinessException("秒传源文件缺少 objectKey，fileId=" + fileId);
        }

        FileResource file = new FileResource();
        file.setId(fileId);
        file.setFolderId(msg.getTargetFolderId() == null ? 0L : msg.getTargetFolderId());
        file.setUserId(msg.getUserId());
        file.setFileName(msg.getFileName());
        file.setOriginalFileName(msg.getFileName());
        file.setFileUrl(msg.getObjectKey());
        file.setFileHash(msg.getFileHash());
        file.setFileType(StringUtils.getFilenameExtension(msg.getFileName()));
        file.setFileSize(msg.getFileSize());
        file.setSource(1);
        file.setUploadStatus(1);
        file.setParseStatus(0);
        LocalDateTime now = LocalDateTime.now();
        file.setCreateTime(now);
        file.setUpdateTime(now);
        fileResourceMapper.insert(file);

        // 与上面的 insert 同一事务：空间不足时回滚记录，避免"文件已入库但容量没扣"
        if (!userFileQuotaService.addUserSpace(msg.getUserId(), msg.getFileSize())) {
            throw new BusinessException("存储空间不足，秒传文件保存失败，userId=" + msg.getUserId());
        }
        log.info("秒传文件入库成功，fileId: {}, userId: {}, fileSize: {}", fileId, msg.getUserId(), msg.getFileSize());
    }

    @Override
    public String getMultipartUploadSignUrl(String fileHash, int partNumber, Long userId) {
        String uploadKey = "yun:upload:task:" + fileHash;
        String taskJson = redisTemplate.opsForValue().get(uploadKey);
        if(taskJson==null){
            throw new RuntimeException("上传的任务不存在");
        }

        try {
            UploadTask uploadTask = fromJson(taskJson, UploadTask.class);
            String presignedUrl = aliOSSUtils.generatePresignedUrl(uploadTask.objectKey, uploadTask.uploadId, partNumber, 900);

            return presignedUrl;
        } catch (Exception e) {
            log.error("解析上传任务失败", e);
            throw new RuntimeException("解析上传任务失败",e);
        }
    }

    /**
     *
     * @param fileHash
     * @param chunkNumber
     * @param eTag
     * @param userId
     */
    @Override
    public void notifyChunkUploadDone(String fileHash, int chunkNumber, String eTag, Long userId) {
        String uploadKey = "yun:upload:task:" + fileHash;
        String taskJson = redisTemplate.opsForValue().get(uploadKey);
        if(taskJson==null){
            throw new RuntimeException("上传的任务不存在");
        }

        try {
            UploadTask uploadTask = fromJson(taskJson, UploadTask.class);
            uploadTask.partETags.add(new PartETag(chunkNumber,eTag));

            redisTemplate.opsForValue().set(uploadKey,toJson(uploadTask),30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("更新分片信息失败", e);
            throw new RuntimeException("更新分片信息失败",e);
        }
    }


    @Override
    @Transactional
    public Map<String, Object> mergeChunks(String fileHash, Long fileId, Long userId) {
        String uploadKey = "yun:upload:task:" + fileHash;
        String taskJson = redisTemplate.opsForValue().get(uploadKey);
        if(taskJson==null){
            throw new RuntimeException("上传的任务不存在");
        }

        try {
            UploadTask uploadTask = fromJson(taskJson, UploadTask.class);
            List<com.aliyun.oss.model.PartETag> partETags = uploadTask.partETags.stream()
                    .map(p -> p.toOssPartETag())
                    .collect(Collectors.toList());

            String completeMultipartUploadURL = aliOSSUtils.completeMultipartUpload(uploadTask.objectKey, uploadTask.uploadId, partETags);

            redisTemplate.delete(uploadKey);

            // 查询文件大小，更新用户已用空间
            FileResource file = fileResourceMapper.selectById(fileId);
            if (file != null && file.getFileSize() != null) {
                // addUserSpace 改用原子 UPDATE，超限时返回 false 而非抛异常，这里必须显式判断
                if (!userFileQuotaService.addUserSpace(userId, file.getFileSize())) {
                    throw new BusinessException("存储空间不足，无法完成上传");
                }
            }

            LambdaUpdateWrapper<FileResource> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(FileResource::getId,fileId)
                    .set(FileResource::getUploadStatus,1)
                    .set(FileResource::getFileUrl,uploadTask.objectKey);
            fileResourceMapper.update(null,wrapper);
            //TODO: 触发文件解析和向量化处理（异步任务）
//            rabbitTemplate.convertAndSend(TEACHER_RESOURCE_DIRECT_EXCHANGE,ROUTING_KEY_TEACHER_RESOURCE_PARSE, fileId);
            return Map.of("url", completeMultipartUploadURL);
        } catch (Exception e) {
            log.error("合并分片信息失败", e);
            throw new RuntimeException("合并分片信息失败",e);
        }
    }

    @Override
    public String cancelUpload(String fileHash, Long userId) {
        String uploadKey = "yun:upload:task:" + fileHash;
        String taskJson = redisTemplate.opsForValue().get(uploadKey);
        if(taskJson==null){
            throw new RuntimeException("上传的任务不存在");
        }

        try{
            UploadTask uploadTask = fromJson(taskJson, UploadTask.class);
            aliOSSUtils.abortMultipartUpload(uploadTask.objectKey,uploadTask.uploadId);

            // 清理Redis缓存
            redisTemplate.delete(uploadKey);

            // 更新数据库文件状态为上传失败
            LambdaQueryWrapper<FileResource> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(FileResource::getUserId, userId)
                    .eq(FileResource::getFileUrl, uploadTask.objectKey);

            FileResource file = fileResourceMapper.selectOne(queryWrapper);
            if (file != null) {
                file.setUploadStatus(2);
                fileResourceMapper.updateById(file);
            }
        } catch (Exception e) {
            log.error("文件取消上传失败", e);
            throw new RuntimeException("文件取消上传失败",e);
        }
        return "文件已取消上传";
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UploadTask {
        private String fileHash;
        private String objectKey;
        private String uploadId;
        private List<Integer> uploadedParts;
        private List<PartETag> partETags;
    }

    public String toJson(Object obj){
        try{
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("序列化对象失败", e);
            throw new RuntimeException("序列化失败", e);
        }
    }

    public <T>T fromJson(String json,Class<T> clazz){
        try{
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("反序列化对象失败", e);
            throw new RuntimeException("反序列化失败", e);
        }
    }

    // 用于Redis存储的PartETag信息类（可序列化）
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartETag {
        private Integer partNumber;
        private String eTag;

        // 转换为OSS的PartETag对象
        public com.aliyun.oss.model.PartETag toOssPartETag() {
            return new com.aliyun.oss.model.PartETag(partNumber, eTag);
        }

        // 从OSS的PartETag对象创建
        public static PartETag fromOssPartETag(com.aliyun.oss.model.PartETag partETag) {
            return new PartETag(partETag.getPartNumber(), partETag.getETag());
        }
    }

    @Override
    public String getFileAccessUrl(Long id, Long userId) {
        LambdaQueryWrapper<FileResource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileResource::getId, id)
                .eq(FileResource::getUserId, userId);
        FileResource file = fileResourceMapper.selectOne(wrapper);
        if (file == null) {
            throw new BusinessException("文件不存在或无权访问该文件");
        }
        if (file.getFileUrl() == null || file.getFileUrl().isEmpty()) {
            throw new BusinessException("文件路径不存在");
        }
        return aliOSSUtils.getPublicUrl(file.getFileUrl());
    }
}
