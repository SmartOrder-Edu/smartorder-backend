package com.smartorder.edu.web;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 与 {@link SmartorderWebApplicationTests} 重复的上下文冒烟测试，保留但不在打包阶段执行。
 */
@Disabled("需要完整环境变量与外部 MySQL/Redis，打包阶段跳过")
@SpringBootTest
class WebApplicationTests {

    @Test
    void contextLoads() {
    }

}
