package com.smartorder.edu.common.domain.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public class ChunkUploadDTO {

    @Data
    public static class InitReq {
        private String fileHash;        // 文件MD5
        private String fileName;        // 原始文件名
        private Long fileSize;          // 文件总大小
        private Long knowledgeBaseId;   // 所属知识库ID
    }

    @Data
    public static class InitResp {
        private Boolean isFinished;     // 是否已完成（秒传机制）
        private String fileUrl;         // 如果秒传成功，直接返回文件URL
        private String uploadId;        // OSS分片上传的唯一标识
        private List<Integer> uploadedParts; // 已上传的分片序号（用于断点续传）
    }

    @Data
    public static class MergeReq {
        private String uploadId;
        private String fileHash;
        private String fileName;
        private Long fileSize;
        private Long knowledgeBaseId;
        private Integer totalParts;     // 总分片数
    }
}
