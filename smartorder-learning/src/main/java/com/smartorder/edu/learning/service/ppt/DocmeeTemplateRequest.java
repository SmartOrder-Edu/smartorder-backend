package com.smartorder.edu.learning.service.ppt;

import lombok.Data;

@Data
public class DocmeeTemplateRequest {

    private String style;

    private String scene;

    private Integer size = 1;

    private DocmeeTemplateOptions options;
}
