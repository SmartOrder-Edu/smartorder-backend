package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 学生画像更新协调器，以非阻塞异步方式提交画像提取任务
 */
@Slf4j
@Component
public class ProfileUpdateCoordinator {

    private final Executor profileUpdateExecutor;
    private final ProfileUpdateCandidateExtractor candidateExtractor;
    private final UpdateStudentProfileTool updateStudentProfileTool;

    /** 构造方法，注入异步执行器、候选提取器和画像更新工具 **/
    public ProfileUpdateCoordinator(
            @Qualifier("profileUpdateExecutor") Executor profileUpdateExecutor,
            ProfileUpdateCandidateExtractor candidateExtractor,
            UpdateStudentProfileTool updateStudentProfileTool
    ) {
        this.profileUpdateExecutor = profileUpdateExecutor;
        this.candidateExtractor = candidateExtractor;
        this.updateStudentProfileTool = updateStudentProfileTool;
    }

    /** 提交异步画像分析任务，快速校验后交给后台线程执行 **/
    public void submit(
            Long userId,
            String conversationId,
            String traceId,
            String currentUserMessage,
            List<ChatMessage> conversationHistory
    ) {
        if (userId == null || userId <= 0 || currentUserMessage == null || currentUserMessage.isBlank()) {
            log.info("[ProfileUpdate] 跳过画像分析(参数无效): userId={}, traceId={}", userId, traceId);
            return;
        }

        try {
            profileUpdateExecutor.execute(() ->
                    extractAndUpdate(userId, conversationId, traceId, currentUserMessage, conversationHistory));
            log.info("[ProfileUpdate] 已提交后台任务: userId={}, conversationId={}, traceId={}, "
                    + "historySize={}, msg={}",
                    userId, conversationId, traceId,
                    conversationHistory != null ? conversationHistory.size() : 0,
                    truncate(currentUserMessage));
        } catch (RejectedExecutionException e) {
            log.warn("[ProfileUpdate] 后台队列已满，跳过本次画像分析: userId={}, traceId={}",
                    userId, traceId);
        }
    }

    /** 在后台线程中调用LLM提取候选信息并执行画像更新 **/
    private void extractAndUpdate(
            Long userId,
            String conversationId,
            String traceId,
            String currentUserMessage,
            List<ChatMessage> conversationHistory
    ) {
        try {
            log.info("[ProfileUpdate] 后台任务开始执行: userId={}, traceId={}, msg={}",
                    userId, traceId, truncate(currentUserMessage));
            UpdateStudentProfileRequest request = candidateExtractor.extract(currentUserMessage, conversationHistory);
            if (request == null || request.getDimensionUpdate() == null  || request.getDimensionUpdate().isEmpty()) {
                log.info("[ProfileUpdate] LLM 未提取到画像更新: userId={}, traceId={}", userId, traceId);
                return;
            }

            log.info("[ProfileUpdate] LLM 提取到画像更新: userId={}, traceId={}, dimensionCount={}, dimensions={}",
                    userId, traceId, request.getDimensionUpdate().size(),
                    request.getDimensionUpdate().stream()
                            .map(UpdateStudentProfileRequest.DimensionUpdate::getDimensionKey)
                            .toList());

            ToolInvokeContext context = ToolInvokeContext.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .traceId(traceId)
                    .currentUserMessage(currentUserMessage)
                    .agentName("ProfileUpdateCoordinator")
                    .build();
            Object toolResult = updateStudentProfileTool.execute(request, context);
            if (isSuccessfulToolResult(toolResult)) {
                log.info("[ProfileUpdate] 画像更新完成: userId={}, traceId={}, result={}",
                        userId, traceId, toolResult);
            } else {
                log.warn("[ProfileUpdate] 画像更新被拒绝或失败: userId={}, traceId={}, result={}",
                        userId, traceId, toolResult);
            }
        } catch (Exception e) {
            log.warn("[ProfileUpdate] 异步画像分析失败，不影响主请求: userId={}, traceId={}, error={}",
                    userId, traceId, e.getMessage(), e);
        }
    }

    private static String truncate(String text) {
        if (text == null) return "null";
        return text.length() <= 50 ? text : text.substring(0, 50) + "...";
    }

    static boolean isSuccessfulToolResult(Object toolResult) {
        if (!(toolResult instanceof Map<?, ?> result)) {
            return false;
        }
        return Boolean.TRUE.equals(result.get("success"));
    }
}
