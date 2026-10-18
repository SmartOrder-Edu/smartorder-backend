package com.smartorder.edu.manage.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.common.mapper.UserMapper;
import com.smartorder.edu.common.model.entity.User;
import com.smartorder.edu.manage.domain.dto.AdminUserQueryDTO;
import com.smartorder.edu.manage.domain.dto.AdminUserUpdateDTO;
import com.smartorder.edu.manage.domain.vo.AdminUserVO;
import com.smartorder.edu.manage.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理端用户治理服务实现类
 */
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private static final int ROLE_STUDENT = 0;
    private static final int ROLE_ADMIN = 1;
    private static final int ROLE_TEACHER = 2;
    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_NORMAL = 1;

    private final UserMapper userMapper;

    @Override
    public Page<AdminUserVO> queryUserPage(AdminUserQueryDTO queryDTO) {
        int page = queryDTO.getPage() == null || queryDTO.getPage() < 1 ? 1 : queryDTO.getPage();
        int size = queryDTO.getSize() == null || queryDTO.getSize() < 1 ? 10 : Math.min(queryDTO.getSize(), 100);

        Page<User> pageParam = new Page<>(page, size);
        QueryWrapper<User> wrapper = activeUserWrapper();

        String keyword = queryDTO.getKeyword();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String cleanKeyword = keyword.trim();
            // 管理端检索同时覆盖昵称模糊搜索、邮箱模糊搜索和手机号精确/模糊搜索。
            wrapper.and(w -> w.like("username", cleanKeyword)
                    .or().like("email", cleanKeyword)
                    .or().like("phone", cleanKeyword));
        }
        wrapper.eq(queryDTO.getRole() != null, "role", queryDTO.getRole());
        wrapper.eq(queryDTO.getStatus() != null, "status", queryDTO.getStatus());
        wrapper.orderByDesc("create_time");

        Page<User> poPage = userMapper.selectPage(pageParam, wrapper);
        Page<AdminUserVO> voPage = new Page<>(poPage.getCurrent(), poPage.getSize(), poPage.getTotal());
        List<AdminUserVO> records = poPage.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public Map<String, Long> getUserStats() {
        Map<String, Long> stats = new LinkedHashMap<>();
        // 用户统计只统计未逻辑删除账号，避免注销账号污染后台指标。
        stats.put("totalUsers", userMapper.selectCount(activeUserWrapper()));
        stats.put("normalUsers", userMapper.selectCount(activeUserWrapper().eq("status", STATUS_NORMAL)));
        stats.put("disabledUsers", userMapper.selectCount(activeUserWrapper().eq("status", STATUS_DISABLED)));
        stats.put("adminUsers", userMapper.selectCount(activeUserWrapper().eq("role", ROLE_ADMIN)));
        stats.put("studentUsers", userMapper.selectCount(activeUserWrapper().eq("role", ROLE_STUDENT)));
        return stats;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(Long userId, AdminUserUpdateDTO updateDTO, Long operatorUserId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (updateDTO == null) {
            throw new IllegalArgumentException("更新内容不能为空");
        }

        User existing = userMapper.selectById(userId);
        if (existing == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        validateUpdate(updateDTO);
        protectCurrentAdmin(userId, updateDTO, operatorUserId);

        User updateEntity = new User();
        updateEntity.setId(userId);
        updateEntity.setUsername(blankToNull(updateDTO.getUsername()));
        updateEntity.setEmail(blankToNull(updateDTO.getEmail()));
        updateEntity.setPhone(blankToNull(updateDTO.getPhone()));
        updateEntity.setAvatarUrl(blankToNull(updateDTO.getAvatarUrl()));
        updateEntity.setGender(updateDTO.getGender());
        updateEntity.setBirthday(updateDTO.getBirthday());
        updateEntity.setBio(updateDTO.getBio());
        updateEntity.setRole(updateDTO.getRole());
        updateEntity.setVipLevel(updateDTO.getVipLevel());
        updateEntity.setStatus(updateDTO.getStatus());
        updateEntity.setUpdateTime(LocalDateTime.now());

        userMapper.updateById(updateEntity);
    }

    /**
     * 构造未注销用户的基础查询条件。
     */
    private QueryWrapper<User> activeUserWrapper() {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.and(w -> w.isNull("is_deleted").or().eq("is_deleted", 0));
        return wrapper;
    }

    /**
     * 校验管理端可写字段，提前拦截非法角色、状态和超长文本。
     */
    private void validateUpdate(AdminUserUpdateDTO updateDTO) {
        if (updateDTO.getUsername() != null && updateDTO.getUsername().length() > 20) {
            throw new IllegalArgumentException("用户名不能超过20字");
        }
        if (updateDTO.getBio() != null && updateDTO.getBio().length() > 500) {
            throw new IllegalArgumentException("个人简介不能超过500字");
        }
        if (updateDTO.getEmail() != null && !updateDTO.getEmail().isBlank()
                && !updateDTO.getEmail().matches("^[a-zA-Z0-9_.-]+@[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z0-9]{2,6}$")) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        if (updateDTO.getRole() != null && updateDTO.getRole() != ROLE_STUDENT
                && updateDTO.getRole() != ROLE_ADMIN && updateDTO.getRole() != ROLE_TEACHER) {
            throw new IllegalArgumentException("用户角色不合法");
        }
        if (updateDTO.getStatus() != null && updateDTO.getStatus() != STATUS_DISABLED
                && updateDTO.getStatus() != STATUS_NORMAL) {
            throw new IllegalArgumentException("账号状态不合法");
        }
        if (updateDTO.getGender() != null && (updateDTO.getGender() < 0 || updateDTO.getGender() > 2)) {
            throw new IllegalArgumentException("性别值不合法");
        }
        if (updateDTO.getVipLevel() != null && updateDTO.getVipLevel() < 0) {
            throw new IllegalArgumentException("VIP等级不能小于0");
        }
    }

    /**
     * 防止管理员误把自己禁用或降权，避免后台无人可管。
     */
    private void protectCurrentAdmin(Long targetUserId, AdminUserUpdateDTO updateDTO, Long operatorUserId) {
        if (operatorUserId == null || !targetUserId.equals(operatorUserId)) {
            return;
        }
        if (updateDTO.getStatus() != null && updateDTO.getStatus() == STATUS_DISABLED) {
            throw new IllegalStateException("不能禁用当前登录管理员");
        }
        if (updateDTO.getRole() != null && updateDTO.getRole() != ROLE_ADMIN) {
            throw new IllegalStateException("不能取消当前登录管理员权限");
        }
    }

    /**
     * 空字符串转空值，避免管理端误写入无意义的空白文本。
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 将用户实体转为管理端 VO，密码字段不做任何输出。
     */
    private AdminUserVO convertToVO(User user) {
        AdminUserVO vo = new AdminUserVO();
        BeanUtil.copyProperties(user, vo);
        return vo;
    }
}
