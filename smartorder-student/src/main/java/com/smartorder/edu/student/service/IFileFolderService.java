package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.dto.FolderExportTask;
import com.smartorder.edu.student.domain.vo.FileFolderContentVO;

public interface IFileFolderService {
    FileFolderContentVO getFolderTree(Long userId);
    FileFolderContentVO getFolderById(Long id, Long userId);
    void createFolder(FileFolderDTO dto, Long userId);
    void updateFolder(Long id, String folderName, Long userId);
    void deleteFolder(Long id, Long userId);

    void moveFile(Long id, Long targetFolderId, Long userId);

    void moveFolder(Long id, Long targetParentId, Long userId);

    void batchMoveToFolder(FileFolderContentVO vo, Long userId);

    // ==================== 文件夹导出 ====================

    /** 发起导出任务，返回 taskId */
    String exportFolder(Long folderId, Long userId);

    /** 获取导出进度 */
    FolderExportTask getExportProgress(String taskId, Long userId);

    /** 取消导出 */
    void cancelExport(String taskId, Long userId);

    /** 获取导出文件下载 URL（OSS 预签名 URL） */
    String getExportDownloadUrl(String taskId, Long userId);
}
