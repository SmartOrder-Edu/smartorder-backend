package com.smartorder.edu.common.service;

import com.smartorder.edu.common.model.vo.GithubUserVO;

/**
 * GitHub 认证与调用服务接口
 * @author Antigravity
 * @since 2026/07/12
 */
public interface IGithubService {
    /**
     * 根据授权码 code 向 GitHub 换取用户信息
     * @param code 授权码
     * @return GitHub 用户信息
     */
    GithubUserVO getGithubUser(String code);
}
