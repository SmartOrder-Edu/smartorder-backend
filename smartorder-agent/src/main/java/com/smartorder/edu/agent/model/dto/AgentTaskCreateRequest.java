package com.smartorder.edu.agent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 创建 Agent 对话任务的请求体（{@code POST /agent/tasks}）。
 * <p>
 * 只承载客户端有权决定的字段：任务 ID、助手消息 ID、用户 ID 均由服务端生成，
 * 因此不出现在本 DTO 中，避免客户端伪造并写入其他任务的事件流。
 * <p>
 * 与 {@link AgentChatRequest} 的区别：后者是编排器的内部入参（含服务端字段），
 * 本类是对外传输契约，字段变更不应直接泄漏到编排层。
 */
@Data
@NoArgsConstructor
@Schema(description = "创建 Agent 对话任务请求")
public class AgentTaskCreateRequest {

    /** 用户本轮输入，不能为空白。 */
    @NotBlank(message = "消息内容不能为空")
    @Schema(description = "用户输入内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    /**
     * 客户端稳定幂等键。
     * <p>
     * 同一 requestId 的网络重试必须复用原任务，服务端唯一索引阻止重复执行 Agent。
     */
    @NotBlank(message = "requestId 不能为空")
    @Size(max = 100, message = "requestId 长度不能超过 100")
    @Schema(description = "客户端幂等键，重试时必须保持不变", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    /** 已有会话 ID；为空表示开启新会话。 */
    @Schema(description = "会话 ID，缺省时服务端新建会话")
    private String conversationId;

    @Schema(description = "本轮附带的文件 ID 列表")
    private List<Long> fileIds;

    /** 学习执行页传入的当前环境上下文，用于"这里没听懂"等指代性问题。 */
    @Schema(description = "学习执行页上下文：当前脚本步骤、教学段、画布、时间轴位置等")
    private Map<String, Object> studyContext;

    @Schema(description = "RAG 检索使用的知识库 ID 列表")
    private List<Long> knowledgeBaseIds;

    @Schema(description = "RAG 检索模式：auto / simple / medium / complex，缺省 auto")
    private String ragRetrievalMode;

    @Schema(description = "强制运行模式：REACT / PLAN_AND_EXECUTE，缺省走意图路由")
    private String forceMode;

    @Schema(description = "结构化澄清回答 JSON，优先于 message")
    private String clarificationAnswerJson;
}
