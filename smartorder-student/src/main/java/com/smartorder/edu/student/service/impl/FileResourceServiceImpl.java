package com.smartorder.edu.student.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.exception.BusinessException;
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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
        // 删除阿里云OSS上的文件
        if (file.getFileUrl()!=null&&!file.getFileUrl().isEmpty()) {
            aliOSSUtils.deleteFile(file.getFileUrl());
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

        String objectKey = "yun/resource/" + userId + "/" + targetFolderId + '/' + IdUtil.getSnowflakeNextIdStr() + "/" + fileName.substring(fileName.lastIndexOf('.'));
        // 分片上传唯一标识(凭证)
        String uploadId = aliOSSUtils.initiateMultipartUpload(objectKey);
        // 查询已上传分片列表(断点传续)
        List<Integer> listUploadedParts = aliOSSUtils.listUploadedParts(objectKey, uploadId);

        UploadTask task = new UploadTask(fileHash, objectKey, uploadId, listUploadedParts, null);

        log.info("task:{}",task);
        String uploadTaskKey = "yun:resources:upload:task:" + userId + ":" + fileHash;
        redisTemplate.opsForValue().set(uploadTaskKey,toJson(task),30, TimeUnit.MINUTES);

        // 存储文件元信息，等待分片上传完成后更新URL和状态
        Long fileId = IdUtil.getSnowflakeNextId();
        FileResource file = new FileResource();
        file.setId(fileId);
        file.setFolderId(targetFolderId);
        file.setUserId(userId);
        file.setFileName(fileName);
        file.setOriginalFileName(fileName);
        file.setFileUrl(objectKey);
        file.setFileType(fileName.substring(fileName.lastIndexOf('.') + 1));
        file.setFileSize(fileSize);
        file.setSource(1);
        file.setUploadStatus(0);
        file.setParseStatus(0);
        fileResourceMapper.insert(file);

        log.info("文件记录已经创建，并且已经检查已上传的文件,文件ID：{}，对象Key：{}", fileId, objectKey);
        Map<String,Object> result = Map.of(
                "uploadId", uploadId,
                "uploadedParts", listUploadedParts,
                "fileId", fileId.toString()
        );
        return result;
    }

    @Override
    public String getMultipartUploadSignUrl(String fileHash, int partNumber, Long userId) {
        String uploadKey = "yun:resources:upload:task:" + userId + ":" + fileHash;
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
        String uploadKey = "yun:resources:upload:task:" + userId + ":" + fileHash;
        String taskJson = redisTemplate.opsForValue().get(uploadKey);
        if(taskJson==null){
            throw new RuntimeException("上传的任务不存在");
        }

        try {
            UploadTask uploadTask = fromJson(taskJson, UploadTask.class);
            uploadTask.partETags.add(new PartETag(chunkNumber,eTag));

            redisTemplate.opsForValue().set(uploadKey,toJson(uploadTask),30,TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("更新分片信息失败", e);
            throw new RuntimeException("更新分片信息失败",e);
        }
    }


    @Override
    public Map<String, Object> mergeChunks(String fileHash, Long fileId, Long userId) {
        String uploadKey = "yun:resources:upload:task:" + userId + ":" + fileHash;
        String taskJson = redisTemplate.opsForValue().get(uploadKey);
        if(taskJson==null){
            throw new RuntimeException("上传的任务不存在");
        }

        try {
            UploadTask uploadTask = fromJson(taskJson, UploadTask.class);
            List<com.aliyun.oss.model.PartETag> partETags = uploadTask.partETags.stream().map(p -> p.toOssPartETag()).toList();

            String completeMultipartUploadURL = aliOSSUtils.completeMultipartUpload(uploadTask.objectKey, uploadTask.uploadId, partETags);

            redisTemplate.delete(uploadKey);

            // 查询文件大小，更新用户已用空间
            FileResource file = fileResourceMapper.selectById(fileId);
            if (file != null && file.getFileSize() != null) {
                userFileQuotaService.addUserSpace(userId, file.getFileSize());
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
            log.error("更新分片信息失败", e);
            throw new RuntimeException("更新分片信息失败",e);
        }
    }

    @Override
    public String cancelUpload(String fileHash, Long userId) {
        String uploadKey = "yun:resources:upload:task:" + userId + ":" + fileHash;
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
    public FileDownloadResult downloadFile(Long id, Long userId) {
        LambdaQueryWrapper<FileResource> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileResource::getId, id)
                .eq(FileResource::getUserId, userId);
        FileResource file = fileResourceMapper.selectOne(wrapper);
        if (file == null) {
            throw new BusinessException("文件不存在或无权下载该文件");
        }
        if (file.getFileUrl() == null || file.getFileUrl().isEmpty()) {
            throw new BusinessException("文件路径不存在");
        }
        InputStream inputStream = aliOSSUtils.downloadFile(file.getFileUrl());
        return new FileDownloadResult(file.getFileName(), file.getFileType(), file.getFileSize(), inputStream);
    }
}
