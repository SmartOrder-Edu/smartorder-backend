package com.smartorder.edu.learning.service.ppt;

import lombok.Data;

@Data
public class PptGenerationRequest {

    private Long spaceId;

    private String topic;

    private String templateStyle;

    private Integer targetSlides;

    private String userIntent;
}
