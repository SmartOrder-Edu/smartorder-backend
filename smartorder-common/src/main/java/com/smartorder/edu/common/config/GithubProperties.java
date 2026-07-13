package com.smartorder.edu.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * GitHub OAuth 灞炴€ч厤缃被
 * @author Maq
 * @since 2026/07/12
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "github")
public class GithubProperties {
    /**
     * GitHub OAuth Client ID
     */
    private String clientId;
    /**
     * GitHub OAuth Client Secret
     */
    private String clientSecret = "";
    /**
     * GitHub OAuth 鍥炶皟閲嶅畾鍚?URI
     */
    private String redirectUri = "http://localhost:5172/oauth/callback/github";
}
