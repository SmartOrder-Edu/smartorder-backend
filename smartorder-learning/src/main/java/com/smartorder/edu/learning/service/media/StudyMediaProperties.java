package com.smartorder.edu.learning.service.media;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "study.media")
public class StudyMediaProperties {
    private boolean enabled = false;
    private String rootPath = "D:/smartorder-assets";
    private String publicPrefix = "/study-assets";
    private String ossPrefix = "study-media";
}
