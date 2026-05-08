package com.smartorder.edu.common.config;

import com.smartorder.edu.common.interceptor.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor).excludePathPatterns
                ("/druid/**", "/auth/login", "/auth/register", "/auth/refreshToken", "/templates/error", "/auth/sendVerificationCode/**", "/auth/forget", "/admin/**",
                        "/doc.html", "/swagger-resources/**", "/webjars/**", "/v3/api-docs/**",
                        "/hybridaction/**", "/ppt-builder/**"
                );
    }
}
