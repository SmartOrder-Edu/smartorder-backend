package com.smartorder.edu.learning.domain.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class ResourcePackageVO {
    private String packageId;
    private Long spaceId;
    private Long taskId;
    private Long pathNodeId;
    private String title;
    private String reason;
    private String knowledgePoint;
    private String packageMode;
    /**
     * preview：仅预览方案；generating：确认后已落库并进入后台生成；generated：全部生成完成；failed：生成失败。
     */
    private String status;
    private List<ResourceVO> resources = new ArrayList<>();
    private List<AgentActionVO> agentActions = new ArrayList<>();
}
