package com.smartorder.edu.learning.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "docmee")
public class DocmeeProperties {

    private String baseUrl = "https://docmee.cn";

    private String apiKey;

    private String lang = "zh-CN";

    private Integer generateTokenLimit = 1;

    private Integer renderTokenLimit = 0;

    private Integer connectTimeoutMillis = 10000;

    private Integer readTimeoutMillis = 120000;
}
