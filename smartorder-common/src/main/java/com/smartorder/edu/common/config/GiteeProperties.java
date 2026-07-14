package com.smartorder.edu.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Gitee OAuth 属性配置类
 * @author Maq
 * @since 2026/07/14
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "gitee")
public class GiteeProperties {
    /**
     * Gitee OAuth Client ID
     */
    private String clientId;
    /**
     * Gitee OAuth Client Secret
     */
    private String clientSecret = "";
    /**
     * Gitee OAuth 回调重定向 URI
     */
    private String redirectUri = "http://localhost:5172/oauth/callback/gitee";
}
