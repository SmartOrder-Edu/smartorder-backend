package com.smartorder.edu.agent.profile;

import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool;
import com.smartorder.edu.agent.tool.impl.UpdateStudentProfileTool.UpdateStudentProfileRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 学生画像更新协调器，以非阻塞异步方式提交画像提取任务
 */
@Slf4j
@Component
public class ProfileUpdateCoordinator {

    private static final List<String> PROFILE_EVIDENCE_SIGNALS = List.of(
            "我是", "我的专业", "我的年级", "我目前", "我现在",
            "我准备", "准备考研", "我计划", "我打算", "我的目标",
            "我不会", "我不理解", "我不懂", "我没学会", "我没有学会",
            "没有怎么学会", "没怎么学", "基础不好", "基础薄弱", "基础差",
            "没学会", "没学好", "没有学好", "学得不好", "学不明白", "学不懂", "听不懂",
            "没搞明白", "搞不明白", "搞不懂",
            "我总是", "我一直", "连续做错", "经常做错", "反复出错", "老是出错",
            "我喜欢", "我更喜欢", "我偏好", "我习惯",
            "我通常", "我经常", "每天", "学习效率",
            "我紧张", "我焦虑", "我担心", "压力很大", "好难啊", "好难", "太难了", "太难", "我害怕",
            "大一", "大二", "大三", "大四", "研一", "研二", "本科", "研究生"
    );

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
            String conversationHistory
    ) {
        if (userId == null || userId <= 0 || currentUserMessage == null || currentUserMessage.isBlank()
                || !mayContainProfileEvidence(currentUserMessage)) {
            return;
        }

        try {
            profileUpdateExecutor.execute(() ->
                    extractAndUpdate(userId, conversationId, traceId, currentUserMessage, conversationHistory));
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
            String conversationHistory
    ) {
        try {
            UpdateStudentProfileRequest request = candidateExtractor.extract(currentUserMessage, conversationHistory);
            if (request == null || request.getDimensionUpdate() == null  || request.getDimensionUpdate().isEmpty()) {
                return;
            }

            ToolInvokeContext context = ToolInvokeContext.builder()
                    .userId(userId)
                    .conversationId(conversationId)
                    .traceId(traceId)
                    .currentUserMessage(currentUserMessage)
                    .agentName("ProfileUpdateCoordinator")
                    .build();
            updateStudentProfileTool.execute(request, context);
        } catch (Exception e) {
            log.warn("[ProfileUpdate] 异步画像分析失败，不影响主请求: userId={}, traceId={}, error={}",
                    userId, traceId, e.getMessage());
        }
    }

    /** 快速判断消息是否可能包含画像证据关键词（去除标点后比对） **/
    boolean mayContainProfileEvidence(String message) {
        String normalized = normalizeForMatch(message);
        return PROFILE_EVIDENCE_SIGNALS.stream()
                .anyMatch(signal -> normalized.contains(normalizeForMatch(signal)));
    }

    private static String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");
    }
}
