package com.smartorder.edu.agent.tool.impl;

import com.smartorder.edu.agent.tool.ToolCategory;
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
 * 更新学生画像工具 — 增量更新指定维度
 *
 * 当 Agent 从对话中发现学生的新信息（学习背景、目标、能力、偏好等）时调用。
 * 支持深度合并：新数据与现有数据合并，嵌套 Map 递归合并。
 *
 * @author mq
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateStudentProfileTool implements ToolHandler {

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
     * 各维度的证据信号词，用于校验用户消息中是否包含与该维度相关的明确自述。
     * 信号词覆盖同义表达（如"没学会"、"没学好"、"学得不好"均表示基础薄弱）。
     */
    private static final Map<String, List<String>> DIMENSION_EVIDENCE_SIGNALS = Map.of(
            "learning_background", List.of(
                    "我是", "我的专业", "我的年级", "我目前是", "我现在是", "我在读",
                    "我准备考研", "准备考研", "我计划考研", "我打算考研",
                    "我的学历", "我的基础", "编程经验", "学过", "上过",
                    "大一", "大二", "大三", "大四", "研一", "研二",
                    "本科", "研究生", "博士", "专科", "自学者"
            ),
            "learning_goals", List.of(
                    "我的学习目标", "我的目标是", "我想学", "我计划", "我打算",
                    "我要考", "我想掌握", "我希望", "截止时间", "每天学",
                    "学会", "学到", "目标", "规划", "安排"
            ),
            "ability_profile", List.of(
                    "我擅长", "我不擅长", "我的能力", "我能", "我不能",
                    "理解能力", "分析能力", "编程能力", "调试能力",
                    "做得好", "做得不好", "强项", "弱项", "优势", "短板"
            ),
            "common_misconceptions", List.of(
                    "我不会", "我不理解", "我不懂", "我总是", "我一直", "搞不懂",
                    "连续做错", "经常做错", "我的薄弱点", "易错", "反复错",
                    "没学会", "没学好", "没有学会", "没有学好", "学得不好",
                    "基础不好", "基础薄弱", "基础差", "没怎么学", "没有怎么学",
                    "没搞明白", "搞不明白", "学不明白", "学不懂", "听不懂",
                    "反复出错", "老是出错", "总是错", "经常错"
            ),
            "learning_preference", List.of(
                    "我喜欢", "我更喜欢", "我偏好", "对我来说更容易", "我习惯",
                    "看视频", "看文档", "做练习", "听课", "讲解方式",
                    "倾向于", "更适合", "更习惯", "喜欢的方式"
            ),
            "metacognitive_profile", List.of(
                    "我觉得", "我反思", "我总结", "我的学习方法", "我怎么学",
                    "自我评估", "我的规划", "我的信心", "求助",
                    "学习效率", "时间管理", "自律", "拖延"
            ),
            "subject_profiles", List.of(
                    "这门课", "这个科目", "这门学科", "高数", "线代", "概率",
                    "数据结构", "操作系统", "计算机网络", "离散数学",
                    "编译原理", "数据库", "组成原理", "计算机组成"
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
                + "支持的维度：learning_background（学习背景）, learning_goals（学习目标）, "
                + "ability_profile（综合能力）, common_misconceptions（共性易错模式）, "
                + "learning_preference（学习偏好）, metacognitive_profile（元认知画像）, "
                + "subject_profiles（各科目画像集合）。"
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

            if (!containsExplicitSignal(dimensionKey, evidence)) {
                return reject("维度「" + dimensionKey + "」缺少与画像维度匹配的明确自述证据");
            }

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

    private boolean containsExplicitSignal(String dimensionKey, String evidence) {
        List<String> signals = DIMENSION_EVIDENCE_SIGNALS.getOrDefault(dimensionKey, List.of());
        String normalized = normalizeForMatch(evidence);
        return signals.stream().anyMatch(signal -> normalized.contains(normalizeForMatch(signal)));
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
