package com.smartorder.edu.student.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.model.dto.FileSaveMessage;
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

    /**
     * 秒传文件入库（MQ 消费者调用）。
     * <p>
     * 事务边界在方法内部：文件记录插入与用户已用空间累加同一事务，
     * 任一失败整体回滚，重复投递时由主键冲突保证幂等（不会重复扣容量）。
     *
     * @throws org.springframework.dao.DuplicateKeyException 该 fileId 已入库（幂等命中，消费端视为成功）
     */
    void saveQuickUploadFile(FileSaveMessage msg);

    String getMultipartUploadSignUrl(String fileHash, int partNumber, Long userId);

    void notifyChunkUploadDone(String fileHash, int chunkNumber, String eTag, Long userId);

    Map<String, Object> mergeChunks(String fileHash, Long fileId, Long userId);

    String cancelUpload(String fileHash, Long userId);

    /**
     * 获取文件访问直链：校验归属后返回 OSS 公共读 URL，
     * 预览/下载一律由前端直连 OSS，后端不再代理文件流
     */
    String getFileAccessUrl(Long id, Long userId);

    /** 保留：资源广场预览仍走后端代理流式输出 */
    record FileDownloadResult(String fileName, String fileType, Long fileSize, InputStream inputStream) {}
    
}
