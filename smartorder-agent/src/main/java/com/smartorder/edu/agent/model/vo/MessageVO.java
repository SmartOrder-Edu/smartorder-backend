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
    private String traceId;
    private String reasoningContent;
    /** 思考分段 JSON（与工具调用交错），用于前端还原实时展示效果 */
    private String reasoningSegments;
    private LocalDateTime createTime;
}
