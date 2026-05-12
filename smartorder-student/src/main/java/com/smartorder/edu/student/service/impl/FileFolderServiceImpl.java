package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.domain.dto.FileFolderDTO;
import com.smartorder.edu.student.domain.entity.FileFolder;
import com.smartorder.edu.student.domain.vo.FileFolderVO;
import com.smartorder.edu.student.mapper.FileFolderMapper;
import com.smartorder.edu.student.service.IFileFolderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileFolderServiceImpl implements IFileFolderService {

    private final FileFolderMapper fileFolderMapper;

    @Override
    public List<FileFolderVO> getFolderTree(Long userId) {
        QueryWrapper<FileFolder> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        List<FileFolder> folders = fileFolderMapper.selectList(wrapper);

        List<FileFolderVO> vos = folders.stream().map(f -> {
            FileFolderVO vo = new FileFolderVO();
            BeanUtils.copyProperties(f, vo);
            return vo;
        }).collect(Collectors.toList());

        Map<Long, List<FileFolderVO>> parentMap = vos.stream()
                .filter(f -> f.getParentId() != null)
                .collect(Collectors.groupingBy(FileFolderVO::getParentId));

        vos.forEach(f -> f.setChildren(parentMap.getOrDefault(f.getId(), new ArrayList<>())));

        return vos.stream()
                .filter(f -> f.getParentId() == null || f.getParentId() == 0)
                .collect(Collectors.toList());
    }

    @Override
    public FileFolderVO getFolderById(Long id) {
        FileFolder folder = fileFolderMapper.selectById(id);
        if (folder == null) {
            throw new BusinessException("文件夹不存在");
        }
        FileFolderVO vo = new FileFolderVO();
        BeanUtils.copyProperties(folder, vo);
        return vo;
    }

    @Override
    public FileFolderVO createFolder(FileFolderDTO dto, Long userId) {
        FileFolder folder = new FileFolder();
        folder.setParentId(dto.getParentId());
        folder.setFolderName(dto.getFolderName());
        folder.setUserId(userId);
        folder.setCreatedTime(LocalDateTime.now());
        fileFolderMapper.insert(folder);

        FileFolderVO vo = new FileFolderVO();
        BeanUtils.copyProperties(folder, vo);
        return vo;
    }

    @Override
    public FileFolderVO updateFolder(Long id, String folderName, Long userId) {
        FileFolder folder = fileFolderMapper.selectById(id);
        if (folder == null) {
            throw new BusinessException("文件夹不存在");
        }
        if (!folder.getUserId().equals(userId)) {
            throw new BusinessException("无权修改该文件夹");
        }
        folder.setFolderName(folderName);
        folder.setUpdateTime(LocalDateTime.now());
        fileFolderMapper.updateById(folder);

        FileFolderVO vo = new FileFolderVO();
        BeanUtils.copyProperties(folder, vo);
        return vo;
    }

    @Override
    public void deleteFolder(Long id, Long userId) {
        FileFolder folder = fileFolderMapper.selectById(id);
        if (folder == null) {
            throw new BusinessException("文件夹不存在");
        }
        if (!folder.getUserId().equals(userId)) {
            throw new BusinessException("无权删除该文件夹");
        }
        QueryWrapper<FileFolder> wrapper = new QueryWrapper<>();
        wrapper.eq("parent_id", id);
        if (fileFolderMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("文件夹下存在子文件夹，请先删除子文件夹");
        }
        fileFolderMapper.deleteById(id);
    }
}
