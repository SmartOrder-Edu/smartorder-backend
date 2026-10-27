package com.smartorder.edu.web;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 完整 Spring 上下文的冒烟测试：需要真实的环境变量（DEEPSEEK_API_KEY / MySQL / Redis 等）。
 * 本地与 CI 打包默认不启动外部依赖，统一交给集成测试环境执行。
 */
@Disabled("需要完整环境变量与外部 MySQL/Redis，打包阶段跳过")
@SpringBootTest
class SmartorderWebApplicationTests {

    @Test
    void contextLoads() {
    }

}
