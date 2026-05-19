package com.smartorder.edu.common.model.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkdownSection {
    private Integer level;
    private String title;
    private String headingPath;
    private String content;
}
