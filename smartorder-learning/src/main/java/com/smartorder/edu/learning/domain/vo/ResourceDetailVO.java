package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ResourceDetailVO {
    private ResourceVO resource;
    private ResourceContentVO content;
    private String spaceName;
    private String taskTitle;
}
