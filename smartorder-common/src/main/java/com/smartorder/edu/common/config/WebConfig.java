package com.smartorder.edu.common.config;

import com.smartorder.edu.common.interceptor.LoginInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final LoginInterceptor loginInterceptor;

    @Value("${study.media.root-path:D:/smartorder-assets}")
    private String studyMediaRootPath;

    @Value("${study.media.public-prefix:/study-assets}")
    private String studyMediaPublicPrefix;

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
                        "/hybridaction/**", "/ppt-builder/**", "/resources/bilibili/cover", normalizePublicPattern(studyMediaPublicPrefix)
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String prefix = trimRight(studyMediaPublicPrefix, "/");
        String location = Path.of(studyMediaRootPath).toUri().toString();
        registry.addResourceHandler(prefix + "/**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/");
    }

    private String normalizePublicPattern(String publicPrefix) {
        return trimRight(publicPrefix == null || publicPrefix.isBlank() ? "/study-assets" : publicPrefix, "/") + "/**";
    }

    private String trimRight(String value, String suffix) {
        String result = value == null ? "" : value;
        while (result.endsWith(suffix) && result.length() > suffix.length()) {
            result = result.substring(0, result.length() - suffix.length());
        }
        return result;
    }
}
