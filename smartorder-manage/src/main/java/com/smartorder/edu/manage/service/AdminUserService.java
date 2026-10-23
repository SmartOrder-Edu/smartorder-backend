package com.smartorder.edu.manage.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartorder.edu.manage.domain.dto.AdminUserQueryDTO;
import com.smartorder.edu.manage.domain.dto.AdminUserUpdateDTO;
import com.smartorder.edu.manage.domain.vo.AdminUserVO;

import java.util.Map;

/**
 * 管理端用户治理服务接口
 */
public interface AdminUserService {

    /**
     * 分页查询用户列表
     *
     * @param queryDTO 检索条件
     * @return 用户分页结果
     */
    Page<AdminUserVO> queryUserPage(AdminUserQueryDTO queryDTO);

    /**
     * 获取用户总量、角色、状态统计
     *
     * @return 用户统计指标
     */
    Map<String, Long> getUserStats();

    /**
     * 管理员更新指定用户资料、角色或状态
     *
     * @param userId 目标用户ID
     * @param updateDTO 更新内容
     * @param operatorUserId 当前管理员ID
     */
    void updateUser(Long userId, AdminUserUpdateDTO updateDTO, Long operatorUserId);
}
