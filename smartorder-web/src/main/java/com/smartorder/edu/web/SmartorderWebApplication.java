package com.smartorder.edu.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = {"com.smartorder.edu","com.smartorder.edu.common","com.smartorder.edu.student","com.smartorder.edu.manage","com.smartorder.edu.learning","com.smartorder.edu.knowledge"}
)
@MapperScan({
    "com.smartorder.edu.common.mapper",
    "com.smartorder.edu.student.mapper",
    "com.smartorder.edu.learning.mapper",
    "com.smartorder.edu.assessment.mapper",
    "com.smartorder.edu.agent.mapper",
    "com.smartorder.edu.knowledge.mapper"
})
@EnableScheduling

public class SmartorderWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartorderWebApplication.class, args);
    }

}
