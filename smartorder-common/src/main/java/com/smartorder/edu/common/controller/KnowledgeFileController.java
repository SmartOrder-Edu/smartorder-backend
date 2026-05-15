package com.smartorder.edu.common.controller;

import com.smartorder.edu.common.model.dto.ChunkUploadDTO;
import com.smartorder.edu.common.service.IKnowledgeFileService;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/knowledge/file")
@RequiredArgsConstructor
public class KnowledgeFileController {

    private final IKnowledgeFileService knowledgeFileService;

    /**
     * 1. 检查并初始化分片上传 (支持秒传和断点检查)
     */
    @PostMapping("/upload/init")
    public ChunkUploadDTO.InitResp initUpload(@Validated @RequestBody ChunkUploadDTO.InitReq req) {
        Claims claims = ThreadLocalUtils.get();

        Long userId = claims.get("userId", Long.class);
        return knowledgeFileService.initMultipartUpload(req, userId);
    }

    /**
     * 2. 上传文件分片
     */
    @PostMapping("/upload/chunk")
    public Boolean uploadChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("partNumber") Integer partNumber,
            @RequestParam("file") MultipartFile file) {

        knowledgeFileService.uploadPart(uploadId, partNumber, file);
        return true;
    }

    /**
     * 3. 所有分片上传完成，请求合并
     */
    @PostMapping("/upload/merge")
    public String mergeChunks(@Validated @RequestBody ChunkUploadDTO.MergeReq req) {

        Claims claims = ThreadLocalUtils.get();

        Long userId = claims.get("userId", Long.class);
        return knowledgeFileService.completeMultipartUpload(req, userId);

    }
}
