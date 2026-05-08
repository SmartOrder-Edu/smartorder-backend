package com.smartorder.edu.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication(
        scanBasePackages = {"com.smartorder.edu","com.smartorder.edu.common","com.smartorder.edu.student","com.smartorder.edu.manage"}
)
@MapperScan({"com.smartorder.edu.common.mapper"})
public class SmartorderWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartorderWebApplication.class, args);
    }

}
