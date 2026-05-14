package com.smartorder.edu.student.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.student.dto.FileResourceDTO;
import com.smartorder.edu.student.domain.entity.FileResource;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.mapper.FileResourceMapper;
import com.smartorder.edu.student.service.IFileResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileResourceServiceImpl implements IFileResourceService {

    private final FileResourceMapper fileResourceMapper;

    @Override
    public Page<FileResourceVO> getFilesByFolder(Long folderId, Long userId, Integer pageNum, Integer pageSize) {
        QueryWrapper<FileResource> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (folderId != null) {
            wrapper.eq("folder_id", folderId);
        }
        wrapper.orderByDesc("create_time");

        Page<FileResource> page = new Page<>(pageNum, pageSize);
        Page<FileResource> result = fileResourceMapper.selectPage(page, wrapper);

        List<FileResourceVO> vos = result.getRecords().stream().map(r -> {
            FileResourceVO vo = new FileResourceVO();
            BeanUtils.copyProperties(r, vo);
            return vo;
        }).collect(Collectors.toList());

        Page<FileResourceVO> voPage = new Page<>(pageNum, pageSize, result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public FileResourceVO getFileById(Long id) {
        FileResource file = fileResourceMapper.selectById(id);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        FileResourceVO vo = new FileResourceVO();
        BeanUtils.copyProperties(file, vo);
        return vo;
    }

    @Override
    public FileResourceVO createFile(FileResourceDTO dto, Long userId) {
        FileResource file = new FileResource();
        BeanUtils.copyProperties(dto, file);
        file.setUserId(userId);
        file.setParseStatus(0);
        file.setCreateTime(LocalDateTime.now());
        fileResourceMapper.insert(file);

        FileResourceVO vo = new FileResourceVO();
        BeanUtils.copyProperties(file, vo);
        return vo;
    }

    @Override
    public void deleteFile(Long id, Long userId) {
        FileResource file = fileResourceMapper.selectById(id);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        if (!file.getUserId().equals(userId)) {
            throw new BusinessException("无权删除该文件");
        }
        fileResourceMapper.deleteById(id);
    }

    @Override
    public void updateParseStatus(Long id, Integer parseStatus) {
        FileResource file = fileResourceMapper.selectById(id);
        if (file == null) {
            throw new BusinessException("文件不存在");
        }
        file.setParseStatus(parseStatus);
        file.setUpdateTime(LocalDateTime.now());
        fileResourceMapper.updateById(file);
    }

    @Override
    public List<FileResourceVO> getFilesByParseStatus(Integer parseStatus) {
        QueryWrapper<FileResource> wrapper = new QueryWrapper<>();
        wrapper.eq("parse_status", parseStatus);
        return fileResourceMapper.selectList(wrapper).stream().map(r -> {
            FileResourceVO vo = new FileResourceVO();
            BeanUtils.copyProperties(r, vo);
            return vo;
        }).collect(Collectors.toList());
    }
}
