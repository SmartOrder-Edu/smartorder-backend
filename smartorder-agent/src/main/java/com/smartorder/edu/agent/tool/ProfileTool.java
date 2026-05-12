package com.smartorder.edu.agent.tool;

import com.smartorder.edu.user.domain.entity.StudentProfile;
import com.smartorder.edu.user.service.IStudentProfileService;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 学生画像工具 — Agent 通过此工具了解学生的背景、学习风格和可用时间
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileTool {

    private final IStudentProfileService profileService;

    @Tool("获取当前学生的学习档案，包含学习风格、可用时间、学科偏好、学术水平等信息。" +
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

        return String.format("""
            {
              "hasProfile": true,
              "learningStylePreference": "%s",
              "strengths": "%s",
              "weaknesses": "%s",
              "dailyAvailableMinutes": %d,
              "preferredTimeSlots": "%s",
              "concentrationDurationMinutes": %d,
              "subjectsOfInterest": "%s",
              "academicLevel": "%s",
              "major": "%s",
              "semester": "%s",
              "gpa": %.1f
            }""",
            nvl(profile.getLearningStylePreference()),
            nvl(profile.getStrengths()),
            nvl(profile.getWeaknesses()),
            profile.getDailyAvailableMinutes() != null ? profile.getDailyAvailableMinutes() : 0,
            nvl(profile.getPreferredTimeSlots()),
            profile.getConcentrationDurationMinutes() != null ? profile.getConcentrationDurationMinutes() : 0,
            nvl(profile.getSubjectsOfInterest()),
            nvl(profile.getAcademicLevel()),
            nvl(profile.getMajor()),
            nvl(profile.getSemester()),
            profile.getGpa() != null ? profile.getGpa() : 0.0
        );
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}
