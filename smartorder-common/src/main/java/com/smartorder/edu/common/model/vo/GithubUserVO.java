package com.smartorder.edu.common.model.vo;

import lombok.Data;

/**
 * GitHub 鐢ㄦ埛淇℃伅鍝嶅簲瀵硅薄
 * @author Maq
 * @since 2026/07/12
 */
@Data
public class GithubUserVO {
    /**
     * GitHub 鐢ㄦ埛鐨勫敮涓€鏍囪瘑 id
     */
    private String id;
    /**
     * GitHub 鐧诲綍鍚?login
     */
    private String login;
    /**
     * GitHub 鏄电О name
     */
    private String name;
    /**
     * GitHub 澶村儚鍦板潃 avatar_url
     */
    private String avatarUrl;
    /**
     * GitHub 鐢ㄦ埛閭 email
     */
    private String email;
}
