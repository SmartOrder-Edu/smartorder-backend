package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.service.IStudentProfileService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 学生画像控制器 — JSON 维度画像
 *
 * @author 谢光湘
 * @since 2026-05-18
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/student-profile")
@Tag(name = "学生画像", description = "学生画像的查询和更新接口（JSON维度画像）")
public class StudentProfileController {

    private final IStudentProfileService studentProfileService;

    @GetMapping
    @Operation(summary = "获取当前用户画像", description = "返回当前登录用户的学生画像（JSON维度结构）")
    public Result<StudentProfile> getProfile() {
        Long userId = getCurrentUserId();
        StudentProfile profile = studentProfileService.getByUserId(userId);
        return Result.success(profile);
    }

    @PutMapping
    @Operation(summary = "更新学生画像", description = "更新当前登录用户的学生画像信息（JSON维度结构）")
    public Result<String> updateProfile(@RequestBody StudentProfile profile) {
        Long userId = getCurrentUserId();
        profile.setUserId(userId);
        studentProfileService.saveOrUpdate(profile);
        return Result.success("ok");
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        return claims.get("userId", Long.class);
    }
}
