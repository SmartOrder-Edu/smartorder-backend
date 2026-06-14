package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
import com.smartorder.edu.agent.tool.ToolHandler;
import com.smartorder.edu.agent.tool.ToolInvokeContext;
import com.smartorder.edu.student.service.IStudentProfileService;
import com.smartorder.edu.student.service.IStudentProfileService.DimensionUpdateDTO;
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
 * 更新学生画像工具 — 增量更新指定维度
 *
 * 当 Agent 从对话中发现学生的新信息（薄弱点、偏好、掌握程度等）时调用。
 * 支持深度合并：新数据与现有数据合并，嵌套 Map 递归合并。
 *
 * @author mq
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateStudentProfileTool implements ToolHandler {

    private final IStudentProfileService profileService;

    /** 支持的维度名白名单 */
    private static final Set<String> VALID_DIMENSIONS = Set.of(
            "basic_profile",
            "knowledge_profile",
            "ability_profile",
            "preference_profile",
            "cognitive_profile",
            "weakness_profile",
            "behavior_profile",
            "emotion_profile"
    );

    private static final double MIN_CONFIDENCE = 0.8;

    private static final Map<String, List<String>> DIMENSION_EVIDENCE_SIGNALS = Map.of(
            "basic_profile", List.of(
                    "我是", "我的专业", "我的年级", "我目前是", "我现在是", "我在读",
                    "我准备考研", "准备考研", "我计划考研", "我打算考研",
                    "我的学习目标", "我的目标是"
            ),
            "knowledge_profile", List.of(
                    "我不会", "我不理解", "我不懂", "我总是", "我一直", "搞不懂",
                    "连续做错", "我已经掌握", "我熟悉", "我了解"
            ),
            "ability_profile", List.of("我擅长", "我不擅长", "我的能力", "我能", "我不能"),
            "preference_profile", List.of(
                    "我喜欢", "我更喜欢", "我偏好", "对我来说更容易", "我习惯"
            ),
            "cognitive_profile", List.of(
                    "我喜欢", "我更喜欢", "对我来说更容易", "我习惯", "我理解得更快"
            ),
            "weakness_profile", List.of(
                    "我不会", "我不理解", "我不懂", "我总是", "我一直", "搞不懂",
                    "我没学会", "我没有学会", "没有怎么学会", "没怎么学", "基础不好", "基础薄弱",
                    "连续做错", "经常做错", "我的薄弱点"
            ),
            "behavior_profile", List.of(
                    "我通常", "我经常", "我一般", "每天", "学习效率", "我习惯"
            ),
            "emotion_profile", List.of(
                    "我紧张", "我焦虑", "我害怕", "我担心", "压力很大", "一到考试"
            )
    );

    @Override
    public String name() {
        return "updateStudentProfile";
    }

    @Override
    public String description() {
        return "仅在当前用户消息中存在明确自述证据时，更新学生画像的指定维度（支持同时更新多个维度）。"
                + "普通提问和一次性格式要求不属于画像证据。"
                + "支持的维度：basic_profile, knowledge_profile, ability_profile, "
                + "preference_profile, cognitive_profile, weakness_profile, behavior_profile, emotion_profile。"
                + "数据会与现有画像深度合并，不会覆盖其他维度。"
                + "每项必须提供当前用户原话 evidence、0.8 以上的 confidence 和明确 reason。"
                + "如果一次对话中发现多个维度的新信息，请在 dimensionUpdate 数组中传入多项，一次调用完成所有更新。";
    }

    @Override
    public Class<?> requestType() {
        return UpdateStudentProfileRequest.class;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.PROFILE_UPDATE;
    }

    @Override
    @SuppressWarnings("unchecked")
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
        List<DimensionUpdateDTO> dtoList = new ArrayList<>();
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
            if (!currentUserMessage.contains(evidence)) {
                return reject("evidence 必须原样来自当前用户消息: " + evidence);
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

            if (!containsExplicitSignal(dimensionKey, evidence)) {
                return reject("维度「" + dimensionKey + "」缺少与画像维度匹配的明确自述证据");
            }

            dtoList.add(new DimensionUpdateDTO(dimensionKey, delta, reason));
            updatedDimensions.add(dimensionKey);
        }

        log.info("[UpdateStudentProfile] userId={}, dimensions={}, count={}", userId, updatedDimensions, dtoList.size());

        try {
            if (dtoList.size() == 1) {
                // 单维度走原有逻辑
                DimensionUpdateDTO single = dtoList.get(0);
                profileService.updateProfileDimension(userId, single.dimensionKey(), single.delta(), single.reason());
            } else {
                // 多维度批量更新：一次 DB 读写，版本号只递增一次
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

    private boolean containsExplicitSignal(String dimensionKey, String evidence) {
        List<String> signals = DIMENSION_EVIDENCE_SIGNALS.getOrDefault(dimensionKey, List.of());
        return signals.stream().anyMatch(evidence::contains);
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
            @Description("画像维度名称，可选值：basic_profile, knowledge_profile, ability_profile, "
                    + "preference_profile, cognitive_profile, weakness_profile, behavior_profile, emotion_profile")
            private String dimensionKey;

            @Description("该维度的增量数据 JSON 对象，将与现有数据深度合并。"
                    + "例如 {\"weak_points\": [{\"topic\": \"进程与线程\", \"weakness_type\": \"concept_confusion\"}]}")
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
