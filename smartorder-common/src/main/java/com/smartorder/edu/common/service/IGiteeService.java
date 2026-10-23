package com.smartorder.edu.common.service;

import com.smartorder.edu.common.model.vo.GiteeUserVO;

/**
 * Gitee 认证与调用服务接口
 * @author Maq
 * @since 2026/07/14
 */
public interface IGiteeService {
    /**
     * 根据授权码 code 向 Gitee 换取用户信息
     * @param code 授权码
     * @return Gitee 用户信息
     */
    GiteeUserVO getGiteeUser(String code);
}
