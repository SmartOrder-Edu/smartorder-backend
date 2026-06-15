package com.smartorder.edu.student.controller;

import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.student.domain.dto.ProfileInitDTO;
import com.smartorder.edu.student.domain.entity.StudentProfile;
import com.smartorder.edu.student.service.IStudentProfileService;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 学生动态学习画像接口。
 *
 * @author 谢光湘
 * @since 2026-05-18
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/student-profile")
@Tag(name = "学生画像", description = "学生多维动态画像的查询、初始化和增量更新接口")
public class StudentProfileController {

    private final IStudentProfileService studentProfileService;

    @GetMapping
    @Operation(summary = "获取当前用户画像", description = "返回当前登录用户的多维动态学生画像")
    public Result<StudentProfile> getProfile() {
        return Result.success(studentProfileService.getByUserId(getCurrentUserId()));
    }

    @PutMapping
    @Operation(
            summary = "更新学生画像",
            description = "深度合并当前用户提交的画像维度，忽略请求中的主键、用户ID、版本和系统评分字段"
    )
    public Result<StudentProfile> updateProfile(@RequestBody StudentProfile profile) {
        StudentProfile updated = studentProfileService.updateProfile(getCurrentUserId(), profile);
        return Result.success(updated, "学生画像更新成功");
    }

    @PostMapping("/init")
    @Operation(summary = "引导页初始化画像", description = "合并引导页数据，不覆盖已由对话或测评形成的画像")
    public Result<StudentProfile> initProfile(@RequestBody ProfileInitDTO dto) {
        StudentProfile profile = studentProfileService.initProfile(getCurrentUserId(), dto);
        return Result.success(profile, "学生画像初始化成功");
    }

    @PatchMapping("/dimensions/{dimensionKey}")
    @Operation(summary = "增量更新画像维度", description = "深度合并一个画像维度并递增画像版本")
    public Result<StudentProfile> updateDimension(
            @PathVariable String dimensionKey,
            @RequestBody DimensionPatchRequest request
    ) {
        if (request == null) {
            throw new BusinessException("画像维度更新内容不能为空");
        }
        StudentProfile profile = studentProfileService.updateProfileDimension(
                getCurrentUserId(),
                dimensionKey,
                request.delta(),
                request.reason()
        );
        return Result.success(profile, "画像维度更新成功");
    }

    private Long getCurrentUserId() {
        Claims claims = ThreadLocalUtils.get();
        if (claims == null) {
            throw new BusinessException("用户未登录");
        }
        Object userId = claims.get("userId");
        if (!(userId instanceof Number number)) {
            throw new BusinessException("登录信息中缺少有效用户ID");
        }
        return number.longValue();
    }

    public record DimensionPatchRequest(Map<String, Object> delta, String reason) {
    }
}
