package com.smartorder.edu.student.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.student.domain.dto.InitFileUploadDTO;
import com.smartorder.edu.student.domain.vo.FileResourceVO;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface IFileResourceService {
    Page<FileResourceVO> getFilesByFolder(Long folderId, Long userId, Integer pageNum, Integer pageSize);
    FileResourceVO getFileByIdAndUserId(Long id,Long userId);
    void deleteFile(Long id, Long userId);
    void updateParseStatus(Long id, Integer parseStatus);
    List<FileResourceVO> getFilesByParseStatus(Integer parseStatus);

    Map<String, Object> initMultipartUpload(InitFileUploadDTO dto, Long userId);

    String getMultipartUploadSignUrl(String fileHash, int partNumber, Long userId);

    void notifyChunkUploadDone(String fileHash, int chunkNumber, String eTag, Long userId);

    Map<String, Object> mergeChunks(String fileHash, Long fileId, Long userId);

    String cancelUpload(String fileHash, Long userId);

    FileDownloadResult downloadFile(Long id, Long userId);

    record FileDownloadResult(String fileName, String fileType, Long fileSize, InputStream inputStream) {}
    
}
