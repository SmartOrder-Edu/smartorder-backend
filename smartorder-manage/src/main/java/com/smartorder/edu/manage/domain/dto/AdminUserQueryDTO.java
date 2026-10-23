package com.smartorder.edu.manage.domain.dto;

import lombok.Data;

/**
 * 管理端用户分页检索条件 DTO
 */
@Data
public class AdminUserQueryDTO {

    /**
     * 页码
     */
    private Integer page = 1;

    /**
     * 每页数量
     */
    private Integer size = 10;

    /**
     * 用户名/邮箱/手机号检索关键词
     */
    private String keyword;

    /**
     * 用户角色：0=学生，1=管理员，2=教师
     */
    private Integer role;

    /**
     * 账号状态：0=禁用，1=正常
     */
    private Integer status;
}
