package com.smartorder.edu.student.service;

import com.smartorder.edu.student.domain.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.vo.FileFolderVO;

import java.util.List;

public interface IFileFolderService {
    List<FileFolderVO> getFolderTree(Long userId);
    FileFolderVO getFolderById(Long id);
    FileFolderVO createFolder(FileFolderDTO dto, Long userId);
    FileFolderVO updateFolder(Long id, String folderName, Long userId);
    void deleteFolder(Long id, Long userId);
}
