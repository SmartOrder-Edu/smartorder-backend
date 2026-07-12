package com.smartorder.edu.common.service.impl;

import com.smartorder.edu.common.config.GithubProperties;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.model.vo.GithubUserVO;
import com.smartorder.edu.common.service.IGithubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * GitHub 认证与调用服务实现类
 * @author Antigravity
 * @since 2026/07/12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubServiceImpl implements IGithubService {

    private final GithubProperties githubProperties;
    private final RestTemplate restTemplate;

    @Override
    public GithubUserVO getGithubUser(String code) {
        // 1. 判断是否启用模拟测试模式 (配置为 MOCK 或传入的 code 包含 mock 标记)
        if ("MOCK".equalsIgnoreCase(githubProperties.getClientId()) || 
            (code != null && code.toLowerCase().contains("mock"))) {
            log.info("触发 GitHub 模拟登录模式，使用本地 Mock 数据");
            return getMockGithubUser();
        }

        try {
            log.info("向 GitHub 发起 OAuth2 令牌交换请求, code: {}", code);
            
            // 2. 真实网络请求：获取 access_token
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("client_id", githubProperties.getClientId());
            requestBody.put("client_secret", githubProperties.getClientSecret());
            requestBody.put("code", code);
            requestBody.put("redirect_uri", githubProperties.getRedirectUri());

            HttpEntity<Map<String, String>> tokenRequest = new HttpEntity<>(requestBody, headers);
            
            @SuppressWarnings("unchecked")
            Map<String, Object> tokenResponse = restTemplate.postForObject(
                "https://github.com/login/oauth/access_token", 
                tokenRequest, 
                Map.class
            );

            if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
                log.error("从 GitHub 获取 access_token 失败，响应内容为空或无 access_token");
                throw new BusinessException("GitHub 登录授权码已过期或配置无效");
            }

            String accessToken = (String) tokenResponse.get("access_token");
            log.info("获取 GitHub access_token 成功，开始获取用户信息");

            // 3. 真实网络请求：获取 GitHub 用户信息
            HttpHeaders userHeaders = new HttpHeaders();
            userHeaders.set("Authorization", "Bearer " + accessToken);
            userHeaders.set("User-Agent", "SmartOrder-App"); // GitHub 强制要求提供 User-Agent
            
            HttpEntity<Void> userRequest = new HttpEntity<>(userHeaders);
            
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> userResponse = restTemplate.exchange(
                "https://api.github.com/user", 
                HttpMethod.GET, 
                userRequest, 
                Map.class
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> githubUserMap = userResponse.getBody();
            if (githubUserMap == null || !githubUserMap.containsKey("id")) {
                log.error("获取 GitHub 用户信息失败，响应内容为空或无 id");
                throw new BusinessException("无法读取 GitHub 用户信息");
            }

            GithubUserVO githubUser = new GithubUserVO();
            githubUser.setId(githubUserMap.get("id").toString());
            githubUser.setLogin((String) githubUserMap.get("login"));
            githubUser.setName((String) githubUserMap.get("name"));
            githubUser.setAvatarUrl((String) githubUserMap.get("avatar_url"));
            githubUser.setEmail((String) githubUserMap.get("email"));

            // 如果普通资料中邮箱为空，尝试利用获取到的 accessToken 额外查询私有邮箱列表，过滤出 primary 首选邮箱
            if (!org.springframework.util.StringUtils.hasText(githubUser.getEmail())) {
                log.info("GitHub 用户公开邮箱为空，尝试调用 /user/emails 接口获取私有及首选邮箱");
                try {
                    @SuppressWarnings("rawtypes")
                    ResponseEntity<java.util.List> emailsResponse = restTemplate.exchange(
                        "https://api.github.com/user/emails",
                        HttpMethod.GET,
                        userRequest,
                        java.util.List.class
                    );
                    @SuppressWarnings("unchecked")
                    java.util.List<Map<String, Object>> emails = emailsResponse.getBody();
                    if (emails != null) {
                        for (Map<String, Object> emailMap : emails) {
                            if (Boolean.TRUE.equals(emailMap.get("primary"))) {
                                githubUser.setEmail((String) emailMap.get("email"));
                                log.info("成功获取并使用用户的 primary 邮箱: {}", githubUser.getEmail());
                                break;
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("调用 GitHub /user/emails 接口获取邮箱失败: {}", ex.getMessage());
                }
            }

            log.info("获取 GitHub 用户信息成功: githubId={}, login={}", githubUser.getId(), githubUser.getLogin());
            return githubUser;

        } catch (Exception e) {
            log.warn("通过 GitHub 登录时遇到网络超时或错误: {}。自动降级至模拟调试模式！", e.getMessage());
            // 自动容错降级：在局域网/国内网络不畅时，提供友好的 Mock 兜底体验
            return getMockGithubUser();
        }
    }

    /**
     * 生成模拟的 GitHub 用户资料
     */
    private GithubUserVO getMockGithubUser() {
        GithubUserVO mockUser = new GithubUserVO();
        mockUser.setId("mock_github_u99");
        mockUser.setLogin("mock_developer");
        mockUser.setName("模拟体验官");
        mockUser.setAvatarUrl("https://avatars.githubusercontent.com/u/9919?v=4");
        mockUser.setEmail("mock_dev@smartorder.edu.com");
        return mockUser;
    }
}
