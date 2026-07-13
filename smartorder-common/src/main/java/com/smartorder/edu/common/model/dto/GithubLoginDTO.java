package com.smartorder.edu.common.model.dto;

import lombok.Data;

/**
 * GitHub 鐧诲綍 DTO 瀵硅薄
 * @author Maq
 * @since 2026/07/12
 */
@Data
public class GithubLoginDTO {
    /**
     * GitHub OAuth 鎺堟潈 code
     */
    private String code;
    /**
     * GitHub OAuth state锛岀敤浜庨槻寰?CSRF 鏀诲嚮
     */
    private String state;
}
