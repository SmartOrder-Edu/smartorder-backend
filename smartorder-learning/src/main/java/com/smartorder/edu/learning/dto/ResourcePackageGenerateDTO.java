package com.smartorder.edu.learning.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ResourcePackageGenerateDTO {
    /**
     * 可选：绑定到当前学习任务。为空时优先使用空间今日任务。
     */
    private Long taskId;

    /**
     * 可选：绑定到学习路径节点。
     */
    private Long pathNodeId;

    /**
     * 可选：用户指定的知识点。为空时根据任务/空间目标推断。
     */
    private String knowledgePoint;

    /**
     * 可选：需要的资源类型。为空时采用 doc/mind_map/quiz/link 的 MVP 组合。
     */
    private List<String> resourceTypes;

    /**
     * 可选：用户自然语言意图，例如“讲简单点”“考前冲刺”。
     */
    private String userIntent;

    /**
     * 可选：资料包模式：task/weakness/review/exam/custom。
     */
    private String packageMode;

    /**
     * 可选：目标学习时长，用于生成说明和资源粒度。
     */
    private Integer targetMinutes;
}
