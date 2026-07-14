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
 * @author Maq
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


        try {
            log.info("向 GitHub 发起 OAuth2 令牌交换请求, code: {}, redirect_uri: [{}]", code, githubProperties.getRedirectUri());
            
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
                log.error("从 GitHub 获取 access_token 失败，响应内容为空或无 access_token。实际响应: {}", tokenResponse);
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
            githubUser.setAccessToken(accessToken);

            // 始终通过 /user/emails 接口获取 primary 邮箱
            // 因为 /user 接口的 email 字段可能是非 primary 的公开邮箱，也可能是 null
            log.info("调用 GitHub /user/emails 接口获取用户的 primary 邮箱");
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
                            log.info("成功获取用户的 primary 邮箱: {}", githubUser.getEmail());
                            break;
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("调用 GitHub /user/emails 接口获取邮箱失败: {}", ex.getMessage());
            }

            // 防御性判断：若始终未获取到 primary 邮箱（理论上不可能，但保留保护），
            // 则回退到 /user 接口的公开邮箱
            if (!org.springframework.util.StringUtils.hasText(githubUser.getEmail())) {
                String publicEmail = (String) githubUserMap.get("email");
                if (org.springframework.util.StringUtils.hasText(publicEmail)) {
                    log.info("未能获取 primary 邮箱，回退使用 /user 接口的公开邮箱: {}", publicEmail);
                    githubUser.setEmail(publicEmail);
                } else {
                    log.error("GitHub 用户 {} 无 primary 邮箱且无公开邮箱，无法完成登录", githubUser.getLogin());
                    throw new BusinessException("无法获取您的 GitHub 账户邮箱，请确保 GitHub 账户已绑定邮箱后重试");
                }
            }

            log.info("获取 GitHub 用户信息成功: githubId={}, login={}", githubUser.getId(), githubUser.getLogin());
            return githubUser;

        } catch (Exception e) {
            log.error("通过 GitHub 登录时遇到网络超时或错误: {}", e.getMessage(), e);
            throw new BusinessException("GitHub 登录失败，请稍后重试");
        }
    }

}
