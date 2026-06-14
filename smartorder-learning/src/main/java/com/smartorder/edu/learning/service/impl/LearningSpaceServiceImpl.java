package com.smartorder.edu.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.learning.dto.LearningSpaceDTO;
import com.smartorder.edu.learning.domain.entity.LearningSpace;
import com.smartorder.edu.learning.domain.vo.LearningSpaceVO;
import com.smartorder.edu.learning.mapper.LearningSpaceMapper;
import com.smartorder.edu.learning.service.ILearningSpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 学习空间服务实现类
 * @author 谢光湘
 * @since 2026/5/12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningSpaceServiceImpl implements ILearningSpaceService {

    private final LearningSpaceMapper learningSpaceMapper;

    @Override
    public LearningSpaceVO createSpace(Long userId, LearningSpaceDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BusinessException("空间名称不能为空");
        }
        LearningSpace space = new LearningSpace();
        BeanUtils.copyProperties(dto, space);
        space.setUserId(userId);
        space.setProgress(0.0);
        space.setStatus("active");
        if (space.getType() == null) {
            space.setType("course");
        }
        if (space.getSortOrder() == null) {
            space.setSortOrder(0);
        }
        space.setIsDeleted(0);
        learningSpaceMapper.insert(space);
        log.info("用户{}创建学习空间成功，空间ID: {}", userId, space.getId());
        return toVO(space);
    }

    @Override
    public LearningSpaceVO getSpaceById(Long userId, Long spaceId) {
        LearningSpace space = getAndCheckOwner(userId, spaceId);
        return toVO(space);
    }

    @Override
    public List<LearningSpaceVO> listSpaces(Long userId, String status) {
        QueryWrapper<LearningSpace> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId).eq("is_deleted", 0);
        if (status != null && !status.isBlank()) {
            queryWrapper.eq("status", status);
        }
        queryWrapper.orderByAsc("sort_order").orderByDesc("create_time");
        List<LearningSpace> spaces = learningSpaceMapper.selectList(queryWrapper);
        return spaces.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public LearningSpaceVO updateSpace(Long userId, Long spaceId, LearningSpaceDTO dto) {
        LearningSpace space = getAndCheckOwner(userId, spaceId);
        UpdateWrapper<LearningSpace> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", spaceId).eq("user_id", userId).eq("is_deleted", 0);
        if (dto.getName() != null) {
            updateWrapper.set("name", dto.getName());
        }
        if (dto.getType() != null) {
            updateWrapper.set("type", dto.getType());
        }
        if (dto.getDescription() != null) {
            updateWrapper.set("description", dto.getDescription());
        }
        if (dto.getIconUrl() != null) {
            updateWrapper.set("icon_url", dto.getIconUrl());
        }
        if (dto.getCoverUrl() != null) {
            updateWrapper.set("cover_url", dto.getCoverUrl());
        }
        if (dto.getGoals() != null) {
            updateWrapper.set("goals", dto.getGoals());
        }
        if (dto.getStartDate() != null) {
            updateWrapper.set("start_date", dto.getStartDate());
        }
        if (dto.getTargetEndDate() != null) {
            updateWrapper.set("target_end_date", dto.getTargetEndDate());
        }
        if (dto.getSortOrder() != null) {
            updateWrapper.set("sort_order", dto.getSortOrder());
        }
        learningSpaceMapper.update(null, updateWrapper);
        log.info("用户{}更新学习空间{}成功", userId, spaceId);
        // 查询更新后的数据返回
        return toVO(learningSpaceMapper.selectById(spaceId));
    }

    @Override
    public void deleteSpace(Long userId, Long spaceId) {
        getAndCheckOwner(userId, spaceId);
        UpdateWrapper<LearningSpace> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", spaceId).eq("user_id", userId).eq("is_deleted", 0)
                .set("is_deleted", 1);
        int rows = learningSpaceMapper.update(null, updateWrapper);
        if (rows == 0) {
            throw new BusinessException("删除失败，未找到可删除的学习空间记录");
        }
        log.info("用户{}删除学习空间{}成功，影响行数: {}", userId, spaceId, rows);
    }

    @Override
    public LearningSpaceVO changeStatus(Long userId, Long spaceId, String status) {
        if (status == null || status.isBlank()) {
            throw new BusinessException("状态不能为空");
        }
        if (!List.of("active", "archived", "completed").contains(status)) {
            throw new BusinessException("无效的状态值，允许的值：active/archived/completed");
        }
        LearningSpace space = getAndCheckOwner(userId, spaceId);
        UpdateWrapper<LearningSpace> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", spaceId).eq("user_id", userId).eq("is_deleted", 0)
                .set("status", status);
        if ("completed".equals(status)) {
            updateWrapper.set("actual_end_date", LocalDate.now());
        } else if ("active".equals(status)) {
            updateWrapper.set("actual_end_date", null);
        }
        learningSpaceMapper.update(null, updateWrapper);
        log.info("用户{}变更学习空间{}状态为{}", userId, spaceId, status);
        return toVO(learningSpaceMapper.selectById(spaceId));
    }

    /**
     * 查询空间并校验所属用户
     */
    private LearningSpace getAndCheckOwner(Long userId, Long spaceId) {
        if (spaceId == null) {
            throw new BusinessException("空间ID不能为空");
        }
        LearningSpace space = learningSpaceMapper.selectById(spaceId);
        if (space == null || space.getIsDeleted() == 1) {
            throw new BusinessException("学习空间不存在");
        }
        if (!space.getUserId().equals(userId)) {
            throw new BusinessException("无权访问该学习空间");
        }
        return space;
    }

    /**
     * Entity转VO
     */
    private LearningSpaceVO toVO(LearningSpace space) {
        LearningSpaceVO vo = new LearningSpaceVO();
        BeanUtils.copyProperties(space, vo);
        return vo;
    }
}
