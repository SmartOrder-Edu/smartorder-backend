package com.smartorder.edu.student.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.student.domain.dto.FileResourceDTO;
import com.smartorder.edu.student.domain.vo.FileResourceVO;

import java.util.List;

public interface IFileResourceService {
    Page<FileResourceVO> getFilesByFolder(Long folderId, Long userId, Integer pageNum, Integer pageSize);
    FileResourceVO getFileById(Long id);
    FileResourceVO createFile(FileResourceDTO dto, Long userId);
    void deleteFile(Long id, Long userId);
    void updateParseStatus(Long id, Integer parseStatus);
    List<FileResourceVO> getFilesByParseStatus(Integer parseStatus);
}
