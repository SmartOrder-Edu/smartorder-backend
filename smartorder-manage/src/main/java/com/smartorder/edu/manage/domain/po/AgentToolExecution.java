package com.smartorder.edu.manage.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 智能体工具执行审计实体类
 */
@Data
@NoArgsConstructor
@TableName("agent_tool_execution")
public class AgentToolExecution {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String traceId;
    
    private String conversationId;
    
    private Long userId;
    
    private String toolName;
    
    private String arguments; // JSON字串形式
    
    private String result;    // JSON字串形式
    
    private String status;    // SUCCESS、FAILED、TIMEOUT、REJECTED
    
    private String errorMessage;
    
    private Integer durationMs;
    
    private LocalDateTime createTime;
}
