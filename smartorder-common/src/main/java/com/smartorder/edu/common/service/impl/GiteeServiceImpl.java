package com.smartorder.edu.common.service.impl;

import com.smartorder.edu.common.config.GiteeProperties;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.model.vo.GiteeUserVO;
import com.smartorder.edu.common.service.IGiteeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Gitee 认证与调用服务实现类
 * @author Maq
 * @since 2026/07/14
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiteeServiceImpl implements IGiteeService {

    private final GiteeProperties giteeProperties;
    private final RestTemplate restTemplate;

    @Override
    public GiteeUserVO getGiteeUser(String code) {

        try {
            log.info("向 Gitee 发起 OAuth2 令牌交换请求, code: {}, redirect_uri: [{}]", code, giteeProperties.getRedirectUri());

            // 1. 用 form-encoded 方式换取 access_token（Gitee 要求 form-encoded，非 JSON）
            HttpHeaders tokenHeaders = new HttpHeaders();
            tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
            tokenBody.add("grant_type", "authorization_code");
            tokenBody.add("code", code);
            tokenBody.add("client_id", giteeProperties.getClientId());
            tokenBody.add("client_secret", giteeProperties.getClientSecret());
            tokenBody.add("redirect_uri", giteeProperties.getRedirectUri());

            HttpEntity<MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(tokenBody, tokenHeaders);

            @SuppressWarnings("unchecked")
            Map<String, Object> tokenResponse = restTemplate.postForObject(
                "https://gitee.com/oauth/token",
                tokenRequest,
                Map.class
            );

            if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
                log.error("从 Gitee 获取 access_token 失败，响应内容为空或无 access_token。实际响应: {}", tokenResponse);
                throw new BusinessException("Gitee 登录授权码已过期或配置无效");
            }

            String accessToken = (String) tokenResponse.get("access_token");
            log.info("获取 Gitee access_token 成功，开始获取用户信息");

            // 2. 获取 Gitee 用户信息（access_token 作为 query 参数传递）
            String userApiUrl = "https://gitee.com/api/v5/user?access_token=" + accessToken;

            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> userResponse = restTemplate.getForEntity(userApiUrl, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> giteeUserMap = userResponse.getBody();
            if (giteeUserMap == null || !giteeUserMap.containsKey("id")) {
                log.error("获取 Gitee 用户信息失败，响应内容为空或无 id");
                throw new BusinessException("无法读取 Gitee 用户信息");
            }

            GiteeUserVO giteeUser = new GiteeUserVO();
            giteeUser.setId(giteeUserMap.get("id").toString());
            giteeUser.setLogin((String) giteeUserMap.get("login"));
            giteeUser.setName((String) giteeUserMap.get("name"));
            giteeUser.setAvatarUrl((String) giteeUserMap.get("avatar_url"));
            giteeUser.setAccessToken(accessToken);

            // 3. 通过 /api/v5/emails 接口获取邮箱列表，筛选 primary 邮箱
            log.info("调用 Gitee /api/v5/emails 接口获取用户的 primary 邮箱");
            try {
                String emailsUrl = "https://gitee.com/api/v5/emails?access_token=" + accessToken;
                @SuppressWarnings("rawtypes")
                ResponseEntity<List> emailsResponse = restTemplate.getForEntity(emailsUrl, List.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> emails = emailsResponse.getBody();
                if (emails != null) {
                    for (Map<String, Object> emailMap : emails) {
                        // Gitee 邮箱字段：email, state (confirmed/unconfirmed), scope (primary/public/private)
                        Object stateObj = emailMap.get("state");
                        Object scopeObj = emailMap.get("scope");
                        if ("confirmed".equals(stateObj) && "primary".equals(scopeObj)) {
                            giteeUser.setEmail((String) emailMap.get("email"));
                            log.info("成功获取用户的 primary 邮箱: {}", giteeUser.getEmail());
                            break;
                        }
                    }
                    // 如果没有标记为 primary 的，尝试取第一个 confirmed 的邮箱
                    if (!org.springframework.util.StringUtils.hasText(giteeUser.getEmail())) {
                        for (Map<String, Object> emailMap : emails) {
                            if ("confirmed".equals(emailMap.get("state"))) {
                                giteeUser.setEmail((String) emailMap.get("email"));
                                log.info("未找到 primary 邮箱，使用第一个 confirmed 邮箱: {}", giteeUser.getEmail());
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("调用 Gitee /api/v5/emails 接口获取邮箱失败: {}", ex.getMessage());
            }

            // 4. 如果邮箱接口没有返回，尝试从 /user 接口的 email 字段获取
            if (!org.springframework.util.StringUtils.hasText(giteeUser.getEmail())) {
                String publicEmail = (String) giteeUserMap.get("email");
                if (org.springframework.util.StringUtils.hasText(publicEmail)) {
                    log.info("未能从 /emails 接口获取邮箱，回退使用 /user 接口的公开邮箱: {}", publicEmail);
                    giteeUser.setEmail(publicEmail);
                } else {
                    log.error("Gitee 用户 {} 无可用邮箱，无法完成登录", giteeUser.getLogin());
                    throw new BusinessException("无法获取您的 Gitee 账户邮箱，请确保 Gitee 账户已绑定邮箱后重试");
                }
            }

            log.info("获取 Gitee 用户信息成功: giteeId={}, login={}", giteeUser.getId(), giteeUser.getLogin());
            return giteeUser;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("通过 Gitee 登录时遇到网络超时或错误: {}", e.getMessage(), e);
            throw new BusinessException("Gitee 登录失败，请稍后重试");
        }
    }

}
