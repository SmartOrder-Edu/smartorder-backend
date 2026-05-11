package com.smartorder.edu.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(
        scanBasePackages = {"com.smartorder.edu","com.smartorder.edu.common","com.smartorder.edu.student","com.smartorder.edu.manage"}
)
@MapperScan({
    "com.smartorder.edu.common.mapper",
    "com.smartorder.edu.user.mapper",
    "com.smartorder.edu.learning.mapper",
    "com.smartorder.edu.assessment.mapper",
    "com.smartorder.edu.knowledge.mapper",
    "com.smartorder.edu.agent.mapper"
})
public class SmartorderWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartorderWebApplication.class, args);
    }

}
