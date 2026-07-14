package com.smartorder.edu.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security配置类，定义密码加密器
 * @author 谢光湘
 * @since 2026/2/26
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true) // 开启 @PreAuthorize 注解支持
public class SecurityConfig {
    /**
     * 创建密码加密器
     * @return BCryptPasswordEncoder
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 配置安全过滤链
     * @param http HttpSecurity
     * @return SecurityFilterChain
     * @throws Exception 异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        // 允许的路径需与 WebConfig 及 LoginInterceptor 保持一致
                        .requestMatchers("/druid/**", "/auth/login", "/auth/register", "/auth/refreshToken", "/auth/forget", "/auth/sendVerificationCode/**", "/auth/github/login", "/auth/github/state", "/auth/gitee/login", "/auth/gitee/state", "/templates/error","/doc.html","/swagger-resources/**","/webjars/**","/v3/api-docs/**","/ppt-builder/**").permitAll()
                        //放行所有请求，鉴权逻辑由LoginInterceptor处理，防止Spring Security提前拦截
                        .anyRequest().permitAll()
                )
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(org.springframework.security.config.annotation.web.configurers.HeadersConfigurer.FrameOptionsConfig::disable));
        return http.build();
    }

}