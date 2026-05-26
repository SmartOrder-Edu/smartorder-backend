package com.smartorder.edu.common.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.model.dto.ChunkUploadDTO;
import com.smartorder.edu.common.model.vo.KnowledgeFilePageVO;
import com.smartorder.edu.common.model.vo.Result;
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

    /**
     * 4. 删除文件（级联删除所有相关数据）
     */
    @DeleteMapping("/{id}")
    public String deleteKnowledgeFile(@PathVariable Long id) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        knowledgeFileService.deleteKnowledgeFile(id, userId);
        return "文件删除成功";
    }

    /**
     * 5. 分页查询知识库下的文件
     */
    @GetMapping("/list")
    public Result<Page<KnowledgeFilePageVO>> getFilesByKnowledgeBaseId(
            @RequestParam Long knowledgeBaseId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Claims claims = ThreadLocalUtils.get();
        Long userId = claims.get("userId", Long.class);
        Page<KnowledgeFilePageVO> page = knowledgeFileService.getFilesByKnowledgeBaseId(knowledgeBaseId, userId, pageNum, pageSize);
        return Result.success(page);
    }
}
