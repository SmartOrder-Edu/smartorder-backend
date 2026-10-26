package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.service.impl.ProfileUpdateCoordinator;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.student.domain.dto.StudentProfileDimensionUpdateDTO;
import com.smartorder.edu.student.service.IStudentProfileService;
import dev.langchain4j.model.output.structured.Description;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学生画像更新执行器 — 增量更新指定维度
 *
 * <p>该类不再作为 Agent 可直接调用的 ToolHandler 注册，仅供
 * {@code ProfileUpdateCoordinator} 在异步流程中复用，完成 LLM 提取后的画像写入。
 *
 * <p>支持深度合并：新数据与现有数据合并，嵌套 Map 递归合并。
 *
 * @author mq
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateStudentProfileTool {

    private final IStudentProfileService profileService;

    /**
     * 支持的维度名白名单（snake_case，与 StudentProfile 实体字段一一对应）
     */
    private static final Set<String> VALID_DIMENSIONS = Set.of(
            "learning_background",
            "learning_goals",
            "ability_profile",
            "common_misconceptions",
            "learning_preference",
            "metacognitive_profile",
            "subject_profiles"
    );

    private static final double MIN_CONFIDENCE = 0.8;

    /**
     * 执行画像增量更新。
     *
     * <p>由 {@code ProfileUpdateCoordinator} 在异步线程中调用，校验证据、置信度、信号词后
     * 调用 {@link IStudentProfileService} 写入。
     *
     * @param request 画像更新请求（含维度增量、证据、置信度、原因）
     * @param context 工具调用上下文（提供 userId、当前用户消息等）
     * @return 执行结果 Map：成功包含 success/dimensions/message；失败包含 success=false 及 error
     */
    public Object execute(Object request, ToolInvokeContext context) {
        UpdateStudentProfileRequest req = (UpdateStudentProfileRequest) request;
        Long userId = context.getUserId();
        String currentUserMessage = context.getCurrentUserMessage();

        List<UpdateStudentProfileRequest.DimensionUpdate> updates = req.getDimensionUpdate();
        if (updates == null || updates.isEmpty()) {
            return reject("dimensionUpdate 不能为空");
        }
        if (currentUserMessage == null || currentUserMessage.isBlank()) {
            return reject("缺少可信的当前用户消息，无法校验画像更新证据");
        }

        // 先校验整批数据，再执行任何画像写入，避免部分成功。
        List<StudentProfileDimensionUpdateDTO> dtoList = new ArrayList<>();
        List<String> updatedDimensions = new ArrayList<>();
        for (UpdateStudentProfileRequest.DimensionUpdate item : updates) {
            if (item == null) {
                return reject("dimensionUpdate 中不能包含空项");
            }

            String dimensionKey = item.getDimensionKey();
            if (dimensionKey == null || dimensionKey.isBlank()) {
                return reject("dimensionKey 不能为空");
            }
            dimensionKey = dimensionKey.trim().toLowerCase();
            if (!VALID_DIMENSIONS.contains(dimensionKey)) {
                return reject("不支持的维度: " + dimensionKey + "，支持的维度: " + VALID_DIMENSIONS);
            }

            Map<String, Object> delta = item.getDelta();
            if (delta == null || delta.isEmpty()) {
                return reject("维度「" + dimensionKey + "」的 delta 数据不能为空");
            }

            String evidence = item.getEvidence();
            if (evidence == null || evidence.isBlank()) {
                return reject("维度「" + dimensionKey + "」的 evidence 不能为空");
            }
            evidence = evidence.trim();
            // 去除标点和空白后比对，兼容 LLM 提取时添加/去除标点的情况
            String normalizedMessage = normalizeForMatch(currentUserMessage);
            String normalizedEvidence = normalizeForMatch(evidence);
            if (normalizedEvidence.isEmpty() || !normalizedMessage.contains(normalizedEvidence)) {
                return reject("evidence 必须来自当前用户消息: " + evidence);
            }

            Double confidence = item.getConfidence();
            if (confidence == null
                    || !Double.isFinite(confidence)
                    || confidence < MIN_CONFIDENCE
                    || confidence > 1.0) {
                return reject("维度「" + dimensionKey + "」的 confidence 必须在 0.8 到 1.0 之间");
            }

            String reason = item.getReason();
            if (reason == null || reason.isBlank()) {
                return reject("维度「" + dimensionKey + "」的 reason 不能为空");
            }
            reason = reason.trim();

            dtoList.add(new StudentProfileDimensionUpdateDTO(dimensionKey, delta, reason));
            updatedDimensions.add(dimensionKey);
        }

        log.info("[UpdateStudentProfile] userId={}, dimensions={}, count={}", userId, updatedDimensions, dtoList.size());

        try {
            if (dtoList.size() == 1) {
                profileService.updateProfileDimension(userId, dtoList.get(0));
            } else {
                profileService.updateProfileDimensions(userId, dtoList);
            }
            return Map.of(
                    "success", true,
                    "dimensions", updatedDimensions,
                    "message", "画像维度「" + String.join(", ", updatedDimensions) + "」已更新"
            );
        } catch (Exception e) {
            log.error("[UpdateStudentProfile] 更新失败: userId={}, dimensions={}", userId, updatedDimensions, e);
            return Map.of("success", false, "error", "画像更新失败: " + e.getMessage());
        }
    }

    /**
     * 去除中英文标点和空白，仅保留汉字、字母、数字，用于模糊比对。
     */
    private static String normalizeForMatch(String text) {
        if (text == null) {
            return "";
        }
        // 保留汉字(一-龥)、字母(a-zA-Z)、数字(0-9)，其余全部去除
        return text.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");
    }

    private Map<String, Object> reject(String error) {
        return Map.of(
                "success", false,
                "rejected", true,
                "error", error
        );
    }

    @Data
    public static class UpdateStudentProfileRequest {
        @Description("画像维度增量更新列表；多个有效维度应在一次工具调用中提交")
        private List<DimensionUpdate> dimensionUpdate;

        @Data
        public static class DimensionUpdate {
            @Description("画像维度名称，可选值：learning_background, learning_goals, ability_profile, "
                    + "common_misconceptions, learning_preference, metacognitive_profile, "
                    + "subject_profiles")
            private String dimensionKey;

            @Description("该维度的增量数据 JSON 对象，将与现有数据深度合并。"
                    + "例如 {\"patterns\":{\"PATTERN-001\":{\"title\":\"边界条件遗漏\","
                    + "\"description\":\"用户反复遗漏循环边界\"}}}")
            private Map<String, Object> delta;

            @Description("必须原样摘自当前用户消息的明确自述证据，不能总结、改写或引用助手回复")
            private String evidence;

            @Description("证据置信度，范围 0.8 到 1.0")
            private Double confidence;

            @Description("证据与画像维度之间的明确关系说明")
            private String reason;
        }
    }
}
