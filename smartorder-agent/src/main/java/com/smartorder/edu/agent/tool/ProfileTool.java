package com.smartorder.edu.agent.tool;

import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.service.IStudentProfileService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 学生画像工具 — Agent 通过此工具了解学生的背景、学习风格和可用时间
 *
 * @author 谢光湘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileTool {

    private final IStudentProfileService profileService;

    @Tool("获取当前学生的学习档案，包含基础信息、学习目标、知识基础、薄弱点等。" +
          "在制定学习计划之前，必须先调用此工具了解学生情况。")
    public String getStudentProfile(Long userId) {
        log.info("[ProfileTool] 获取用户 {} 的学习档案", userId);
        StudentProfile profile = profileService.getByUserId(userId);

        if (profile == null) {
            return """
                {
                  "hasProfile": false,
                  "message": "该学生尚未创建学习档案。请先询问学生的以下信息：\
                  1. 专业和年级\
                  2. 每天可用于学习的时间\
                  3. 偏好的学习时间段\
                  4. 感兴趣的学科领域\
                  5. 当前的学习目标"
                }""";
        }

        Map<String, Object> basic = profile.getBasicProfile();
        Map<String, Object> knowledge = profile.getKnowledgeProfile();
        Map<String, Object> weakness = profile.getWeaknessProfile();

        return String.format("""
            {
              "hasProfile": true,
              "profileVersion": %d,
              "confidenceScore": %.1f,
              "basicProfile": %s,
              "knowledgeProfile": %s,
              "weaknessProfile": %s,
              "profileSummary": "%s"
            }""",
            profile.getProfileVersion() != null ? profile.getProfileVersion() : 1,
            profile.getConfidenceScore() != null ? profile.getConfidenceScore() : 0,
            basic != null ? basic.toString() : "{}",
            knowledge != null ? knowledge.toString() : "{}",
            weakness != null ? weakness.toString() : "{}",
            nvl(profile.getProfileSummary())
        );
    }

    @Tool("更新学生画像的某个维度。可更新 basicProfile（基础信息：identity_type, target_direction, " +
          "current_level, primary_goal, self_description 等）、knowledgeProfile、weaknessProfile 等。" +
          "只需传入要修改的 JSON 字段，未传入的字段保持原值不变。")
    public String updateStudentProfile(Long userId, String profileSummary) {
        log.info("[ProfileTool] 更新用户 {} 的学习档案", userId);
        StudentProfile profile = profileService.getByUserId(userId);
        if (profile == null) {
            profile = new StudentProfile();
            profile.setUserId(userId);
            profile.setProfileVersion(1);
        }
        if (profileSummary != null) {
            profile.setProfileSummary(profileSummary);
        }
        profileService.saveOrUpdate(profile);
        return "{\"success\": true, \"message\": \"画像已更新\"}";
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
