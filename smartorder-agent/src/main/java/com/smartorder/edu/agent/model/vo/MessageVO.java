package com.smartorder.edu.agent.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {
    private Long id;
    private String role;
    private String content;
    private String toolName;
    private LocalDateTime createTime;
}
