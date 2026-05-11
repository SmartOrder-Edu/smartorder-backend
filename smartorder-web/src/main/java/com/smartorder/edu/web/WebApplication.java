package com.smartorder.edu.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = {"com.smartorder.edu","com.smartorder.edu.common","com.smartorder.edu.teacher","com.smartorder.edu.manage","com.smartorder.edu.student"}
)
//@MapperScan({"com.smartorder.edu.common.mapper", "com.smartorder.edu.teacher.mapper", "com.smartorder.edu.manage.mapper"})
@EnableScheduling
public class WebApplication {
    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }

}
