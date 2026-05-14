package com.smartorder.edu.common.service;

import com.smartorder.edu.common.domain.dto.ChunkUploadDTO;
import org.springframework.web.multipart.MultipartFile;

public interface IKnowledgeFileService {

    ChunkUploadDTO.InitResp initMultipartUpload(ChunkUploadDTO.InitReq req, Long userId);

    void uploadPart(String uploadId, Integer partNumber, MultipartFile file);

    String completeMultipartUpload(ChunkUploadDTO.MergeReq req, Long userId);
}
