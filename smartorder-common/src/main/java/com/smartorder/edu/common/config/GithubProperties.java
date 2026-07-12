package com.smartorder.edu.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * GitHub OAuth 属性配置类
 * @author Antigravity
 * @since 2026/07/12
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "github")
public class GithubProperties {
    /**
     * GitHub OAuth Client ID，如果为 MOCK 则启用本地调试模式
     */
    private String clientId = "MOCK";
    /**
     * GitHub OAuth Client Secret
     */
    private String clientSecret = "";
    /**
     * GitHub OAuth 回调重定向 URI
     */
    private String redirectUri = "http://localhost:5172/oauth/callback/github";
}
