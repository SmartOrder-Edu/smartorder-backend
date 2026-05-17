package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.vo.FileFolderContentVO;

import java.util.List;

public interface IFileFolderService {
    List<FileFolderContentVO> getFolderTree(Long userId);
    FileFolderContentVO getFolderById(Long id, Long userId);
    void createFolder(FileFolderDTO dto, Long userId);
    void updateFolder(Long id, String folderName, Long userId);
    void deleteFolder(Long id, Long userId);

    void moveFile(Long id, Long targetFolderId, Long userId);

    void moveFolder(Long id, Long targetParentId, Long userId);
}
