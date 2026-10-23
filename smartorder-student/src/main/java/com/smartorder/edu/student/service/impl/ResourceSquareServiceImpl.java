package com.smartorder.edu.student.service.impl;

import cn.hutool.core.util.IdUtil;
import com.aliyun.oss.OSSException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.mapper.UserMapper;
import com.smartorder.edu.common.util.AliOSSUtils;
import com.smartorder.edu.common.model.entity.User;
import com.smartorder.edu.student.domain.dto.ResourceSquareListQueryDTO;
import com.smartorder.edu.student.domain.dto.ResourceSquarePublishDTO;
import com.smartorder.edu.student.domain.dto.ResourceSquareRateDTO;
import com.smartorder.edu.student.domain.entity.FileResource;
import com.smartorder.edu.student.domain.entity.ResourceSquare;
import com.smartorder.edu.student.domain.entity.ResourceSquareRating;
import com.smartorder.edu.student.domain.vo.FileResourceVO;
import com.smartorder.edu.student.domain.vo.ResourceSquareVO;
import com.smartorder.edu.student.mapper.FileResourceMapper;
import com.smartorder.edu.student.mapper.ResourceSquareMapper;
import com.smartorder.edu.student.mapper.ResourceSquareRatingMapper;
import com.smartorder.edu.student.service.IFileResourceService;
import com.smartorder.edu.student.service.IResourceSquareService;
import com.smartorder.edu.student.service.IUserFileQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceSquareServiceImpl implements IResourceSquareService {

    private final ResourceSquareMapper resourceSquareMapper;
    private final ResourceSquareRatingMapper ratingMapper;
    private final FileResourceMapper fileResourceMapper;
    private final UserMapper userMapper;
    private final IUserFileQuotaService userFileQuotaService;
    private final AliOSSUtils aliOSSUtils;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ResourceSquareVO publish(ResourceSquarePublishDTO dto, Long userId) {
        // 验证文件是否属于当前用户
        FileResource file = fileResourceMapper.selectById(dto.getFileId());
        if (file == null || !file.getUserId().equals(userId)) {
            throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.RESOURCE_SQUARE_FILE_NOT_BELONG.getMsg());
        }
        if (file.getUploadStatus() == null || file.getUploadStatus() != 1) {
            throw new com.smartorder.edu.common.exception.BusinessException("文件上传未完成，无法发布");
        }

        // 检查是否已发布
        Long count = resourceSquareMapper.selectCount(
                new LambdaQueryWrapper<ResourceSquare>()
                        .eq(ResourceSquare::getFileId, dto.getFileId())
                        .eq(ResourceSquare::getIsDeleted, 0)
        );
        if (count > 0) {
            throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.RESOURCE_SQUARE_ALREADY_PUBLISHED.getMsg());
        }

        ResourceSquare square = new ResourceSquare();
        square.setTitle(dto.getTitle());
        square.setDescription(dto.getDescription());
        square.setTags(dto.getTags());
        square.setCategory(dto.getCategory());
        square.setUserId(userId);
        square.setFileId(dto.getFileId());
        square.setDownloadCount(0);
        square.setViewCount(0);
        square.setRatingAvg(BigDecimal.ZERO);
        square.setRatingCount(0);
        square.setStatus(1);
        square.setIsDeleted(0);
        square.setCreateTime(LocalDateTime.now());
        square.setUpdateTime(LocalDateTime.now());

        resourceSquareMapper.insert(square);
        log.info("用户 {} 发布资源到广场, squareId={}, fileId={}", userId, square.getId(), dto.getFileId());

        return toVO(square, file, userId);
    }

    @Override
    public void unpublish(Long id, Long userId) {
        ResourceSquare square = resourceSquareMapper.selectById(id);
        if (square == null || square.getIsDeleted() == 1) {
            throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.RESOURCE_SQUARE_NOT_FOUND.getMsg());
        }
        if (!square.getUserId().equals(userId)) {
            throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.FORBIDDEN.getMsg());
        }
        square.setStatus(0);
        square.setUpdateTime(LocalDateTime.now());
        resourceSquareMapper.updateById(square);
        log.info("用户 {} 下架资源, squareId={}", userId, id);
    }

    @Override
    public Page<ResourceSquareVO> list(ResourceSquareListQueryDTO query, Long userId) {
        LambdaQueryWrapper<ResourceSquare> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ResourceSquare::getStatus, 1);
        wrapper.eq(ResourceSquare::getIsDeleted, 0);

        // 分类筛选
        if (StringUtils.hasText(query.getCategory()) && !"all".equals(query.getCategory())) {
            wrapper.eq(ResourceSquare::getCategory, query.getCategory());
        }

        // 关键词搜索
        if (StringUtils.hasText(query.getKeyword())) {
            String kw = query.getKeyword();
            wrapper.and(w -> w
                    .like(ResourceSquare::getTitle, kw)
                    .or()
                    .like(ResourceSquare::getDescription, kw)
                    .or()
                    .like(ResourceSquare::getTags, kw)
            );
        }

        // 排序
        String sortBy = query.getSortBy();
        if ("download".equals(sortBy)) {
            wrapper.orderByDesc(ResourceSquare::getDownloadCount);
        } else if ("rating".equals(sortBy)) {
            wrapper.orderByDesc(ResourceSquare::getRatingAvg);
        } else {
            // 默认按最新
            wrapper.orderByDesc(ResourceSquare::getCreateTime);
        }

        Page<ResourceSquare> page = new Page<>(query.getPageNum(), query.getPageSize());
        Page<ResourceSquare> result = resourceSquareMapper.selectPage(page, wrapper);

        if (result.getRecords().isEmpty()) {
            Page<ResourceSquareVO> emptyPage = new Page<>(query.getPageNum(), query.getPageSize(), 0);
            emptyPage.setRecords(Collections.emptyList());
            return emptyPage;
        }

        // 批量获取关联文件信息
        List<Long> fileIds = result.getRecords().stream()
                .map(ResourceSquare::getFileId)
                .collect(Collectors.toList());
        Map<Long, FileResource> fileMap = fileResourceMapper.selectBatchIds(fileIds).stream()
                .collect(Collectors.toMap(FileResource::getId, f -> f, (a, b) -> a));

        // 批量获取发布者
        List<Long> userIds = result.getRecords().stream()
                .map(ResourceSquare::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> userNameMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));

        // 获取当前用户的评分和转存记录
        List<Long> squareIds = result.getRecords().stream()
                .map(ResourceSquare::getId)
                .collect(Collectors.toList());

        final Map<Long, Integer> userRatingMap;
        final Set<Long> downloadedSquareIds;

        if (userId != null) {
            List<ResourceSquareRating> ratings = ratingMapper.selectList(
                    new LambdaQueryWrapper<ResourceSquareRating>()
                            .in(ResourceSquareRating::getSquareId, squareIds)
                            .eq(ResourceSquareRating::getUserId, userId)
            );
            userRatingMap = ratings.stream()
                    .collect(Collectors.toMap(ResourceSquareRating::getSquareId, ResourceSquareRating::getRating, (a, b) -> a));

            // 查询已转存的：在 file_resource 中找 source=3 且与原始 file_id 同 file_url
            downloadedSquareIds = result.getRecords().stream()
                    .filter(sq -> {
                        FileResource originalFile = fileMap.get(sq.getFileId());
                        if (originalFile == null || originalFile.getFileUrl() == null) return false;
                        Long count_dl = fileResourceMapper.selectCount(
                                new LambdaQueryWrapper<FileResource>()
                                        .eq(FileResource::getUserId, userId)
                                        .eq(FileResource::getSource, 3)
                                        .eq(FileResource::getFileUrl, originalFile.getFileUrl())
                        );
                        return count_dl > 0;
                    })
                    .map(ResourceSquare::getId)
                    .collect(Collectors.toSet());
        } else {
            userRatingMap = Collections.emptyMap();
            downloadedSquareIds = Collections.emptySet();
        }

        List<ResourceSquareVO> vos = result.getRecords().stream().map(sq -> {
            FileResource file = fileMap.get(sq.getFileId());
            ResourceSquareVO vo = toVO(sq, file, null);
            vo.setPublisherName(userNameMap.getOrDefault(sq.getUserId(), "未知用户"));
            if (userId != null) {
                vo.setHasDownloaded(downloadedSquareIds.contains(sq.getId()));
                vo.setUserRating(userRatingMap.get(sq.getId()));
            }
            return vo;
        }).collect(Collectors.toList());

        Page<ResourceSquareVO> voPage = new Page<>(query.getPageNum(), query.getPageSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    @Override
    public ResourceSquareVO getDetail(Long id, Long userId) {
        ResourceSquare square = resourceSquareMapper.selectById(id);
        if (square == null || square.getIsDeleted() == 1 || square.getStatus() == 0) {
            throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.RESOURCE_SQUARE_NOT_FOUND.getMsg());
        }

        // 自增浏览次数
        square.setViewCount(square.getViewCount() + 1);
        resourceSquareMapper.updateById(square);

        FileResource file = fileResourceMapper.selectById(square.getFileId());
        User publisher = userMapper.selectById(square.getUserId());

        ResourceSquareVO vo = toVO(square, file, userId);
        vo.setPublisherName(publisher != null ? publisher.getUsername() : "未知用户");

        // 当前用户评分
        if (userId != null) {
            ResourceSquareRating rating = ratingMapper.selectOne(
                    new LambdaQueryWrapper<ResourceSquareRating>()
                            .eq(ResourceSquareRating::getSquareId, id)
                            .eq(ResourceSquareRating::getUserId, userId)
            );
            vo.setUserRating(rating != null ? rating.getRating() : null);
        }

        // 检查是否已转存
        if (userId != null && file != null && file.getFileUrl() != null) {
            Long dlCount = fileResourceMapper.selectCount(
                    new LambdaQueryWrapper<FileResource>()
                            .eq(FileResource::getUserId, userId)
                            .eq(FileResource::getSource, 3)
                            .eq(FileResource::getFileUrl, file.getFileUrl())
            );
            vo.setHasDownloaded(dlCount > 0);
        }

        return vo;
    }

    @Override
    @Transactional
    public FileResourceVO download(Long id, Long userId) {
        ResourceSquare square = resourceSquareMapper.selectById(id);
        if (square == null || square.getIsDeleted() == 1 || square.getStatus() == 0) {
            throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.RESOURCE_SQUARE_NOT_FOUND.getMsg());
        }

        FileResource originalFile = fileResourceMapper.selectById(square.getFileId());
        if (originalFile == null) {
            throw new com.smartorder.edu.common.exception.BusinessException("原始文件不存在");
        }

        // 检查是否已转存
        if (originalFile.getFileUrl() != null) {
            Long existingCount = fileResourceMapper.selectCount(
                    new LambdaQueryWrapper<FileResource>()
                            .eq(FileResource::getUserId, userId)
                            .eq(FileResource::getSource, 3)
                            .eq(FileResource::getFileUrl, originalFile.getFileUrl())
            );
            if (existingCount > 0) {
                throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.RESOURCE_SQUARE_ALREADY_DOWNLOADED.getMsg());
            }
        }

        // 创建新的 file_resource 记录，复用同一个 OSS URL
        Long newFileId = IdUtil.getSnowflakeNextId();
        FileResource newFile = new FileResource();
        newFile.setId(newFileId);
        newFile.setFolderId(0L); // 根目录
        newFile.setUserId(userId);
        newFile.setFileName(originalFile.getFileName());
        newFile.setOriginalFileName(originalFile.getOriginalFileName());
        newFile.setFileUrl(originalFile.getFileUrl());
        newFile.setFileType(originalFile.getFileType());
        newFile.setFileHash(originalFile.getFileHash());
        newFile.setFileSize(originalFile.getFileSize());
        newFile.setSource(3); // 广场转存
        newFile.setUploadStatus(1);
        newFile.setParseStatus(0);
        newFile.setCreateTime(LocalDateTime.now());
        newFile.setUpdateTime(LocalDateTime.now());

        fileResourceMapper.insert(newFile);

        // 更新用户已用空间
        if (originalFile.getFileSize() != null && originalFile.getFileSize() > 0) {
            userFileQuotaService.addUserSpace(userId, originalFile.getFileSize());
        }

        // 自增下载次数
        square.setDownloadCount(square.getDownloadCount() + 1);
        resourceSquareMapper.updateById(square);

        log.info("用户 {} 转存资源 squareId={}, newFileId={}", userId, id, newFileId);

        FileResourceVO vo = new FileResourceVO();
        BeanUtils.copyProperties(newFile, vo);
        return vo;
    }

    @Override
    @Transactional
    public void rate(Long id, ResourceSquareRateDTO dto, Long userId) {
        ResourceSquare square = resourceSquareMapper.selectById(id);
        if (square == null || square.getIsDeleted() == 1 || square.getStatus() == 0) {
            throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.RESOURCE_SQUARE_NOT_FOUND.getMsg());
        }

        // Upsert: 先查是否已有评分
        ResourceSquareRating existing = ratingMapper.selectOne(
                new LambdaQueryWrapper<ResourceSquareRating>()
                        .eq(ResourceSquareRating::getSquareId, id)
                        .eq(ResourceSquareRating::getUserId, userId)
        );

        if (existing != null) {
            existing.setRating(dto.getRating());
            existing.setCreateTime(LocalDateTime.now());
            ratingMapper.updateById(existing);
        } else {
            ResourceSquareRating rating = new ResourceSquareRating();
            rating.setSquareId(id);
            rating.setUserId(userId);
            rating.setRating(dto.getRating());
            rating.setCreateTime(LocalDateTime.now());
            ratingMapper.insert(rating);
        }

        // 重新计算平均评分
        List<ResourceSquareRating> allRatings = ratingMapper.selectList(
                new LambdaQueryWrapper<ResourceSquareRating>()
                        .eq(ResourceSquareRating::getSquareId, id)
        );
        BigDecimal avg = allRatings.stream()
                .map(r -> BigDecimal.valueOf(r.getRating()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(allRatings.size()), 1, RoundingMode.HALF_UP);

        square.setRatingAvg(avg);
        square.setRatingCount(allRatings.size());
        resourceSquareMapper.updateById(square);

        log.info("用户 {} 对资源 {} 评分: {}", userId, id, dto.getRating());
    }

    @Override
    public IFileResourceService.FileDownloadResult preview(Long id) {
        ResourceSquare square = resourceSquareMapper.selectById(id);
        if (square == null || square.getIsDeleted() == 1 || square.getStatus() == 0) {
            throw new com.smartorder.edu.common.exception.BusinessException(ResultCode.RESOURCE_SQUARE_NOT_FOUND.getMsg());
        }

        FileResource file = fileResourceMapper.selectById(square.getFileId());
        if (file == null || file.getFileUrl() == null || file.getFileUrl().isEmpty()) {
            throw new com.smartorder.edu.common.exception.BusinessException("文件不存在或路径无效");
        }

        InputStream inputStream;
        try {
            inputStream = aliOSSUtils.downloadFile(file.getFileUrl());
        } catch (OSSException e) {
            log.error("OSS文件下载失败, squareId={}, fileUrl={}, errorCode={}", id, file.getFileUrl(), e.getErrorCode(), e);
            throw new com.smartorder.edu.common.exception.BusinessException("文件在云存储中不存在，可能已被删除");
        }

        log.info("用户预览广场资源, squareId={}, fileId={}", id, square.getFileId());
        return new IFileResourceService.FileDownloadResult(file.getFileName(), file.getFileType(), file.getFileSize(), inputStream);
    }

    private ResourceSquareVO toVO(ResourceSquare square, FileResource file, Long userId) {
        ResourceSquareVO vo = new ResourceSquareVO();
        vo.setId(square.getId());
        vo.setTitle(square.getTitle());
        vo.setDescription(square.getDescription());
        vo.setTags(parseTags(square.getTags()));
        vo.setCategory(square.getCategory());
        vo.setUserId(square.getUserId());
        vo.setFileId(square.getFileId());
        vo.setCoverUrl(square.getCoverUrl());
        vo.setDownloadCount(square.getDownloadCount());
        vo.setViewCount(square.getViewCount());
        vo.setRatingAvg(square.getRatingAvg());
        vo.setRatingCount(square.getRatingCount());
        vo.setStatus(square.getStatus());
        vo.setCreateTime(square.getCreateTime());
        vo.setUpdateTime(square.getUpdateTime());

        if (file != null) {
            vo.setFileType(file.getFileType());
            vo.setFileSize(file.getFileSize());
        }

        return vo;
    }

    private List<String> parseTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return Collections.emptyList();
        }
        // 尝试 JSON 数组解析
        if (tagsJson.startsWith("[")) {
            try {
                return objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {});
            } catch (JsonProcessingException e) {
                // fall through to comma-split
            }
        }
        // 逗号分隔
        String[] parts = tagsJson.split(",");
        List<String> result = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
