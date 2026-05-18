package com.smartorder.edu.common.service;

import com.smartorder.edu.common.model.dto.ChunkUploadDTO;
import org.springframework.web.multipart.MultipartFile;

public interface IKnowledgeFileService {

    ChunkUploadDTO.InitResp initMultipartUpload(ChunkUploadDTO.InitReq req, Long userId);

    void uploadPart(String uploadId, Integer partNumber, MultipartFile file);

    String completeMultipartUpload(ChunkUploadDTO.MergeReq req, Long userId);

    /**
     * 删除文件（级联删除所有相关数据）
     */
    void deleteKnowledgeFile(Long fileId, Long userId);
}
