package com.smartorder.edu.common.config;

import com.smartorder.edu.common.interceptor.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final LoginInterceptor loginInterceptor;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Agent SSE 流式对话可能长达数分钟，设置 5 分钟超时
        configurer.setDefaultTimeout(300_000L);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor).excludePathPatterns
                ("/druid/**", "/auth/login", "/auth/register", "/auth/refreshToken", "/templates/error", "/auth/sendVerificationCode/**", "/auth/forget", "/admin/**",
                        "/doc.html", "/swagger-resources/**", "/webjars/**", "/v3/api-docs/**",
                        "/hybridaction/**", "/ppt-builder/**"
                );
    }
}
