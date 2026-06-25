package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StudyMasteryVO {
    private String level;
    private String levelLabel;
    private String hint;
    private String nextSuggestion;
}
