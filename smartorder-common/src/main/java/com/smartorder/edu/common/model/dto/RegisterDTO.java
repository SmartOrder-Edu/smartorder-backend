package com.smartorder.edu.common.model.dto;


import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户注册DTO对象
 * @author 谢光湘
 * @since 2026/2/26
 */
@Data
@NoArgsConstructor
public class RegisterDTO {
    /**
     * 用户名/昵称
     */
    private String username;
    /**
     * 邮箱
     */
    private String email;
    /**
     * 手机号
     */
    private String phone;
    /**
     * 密码
     */
    private String password;
    /**
     * 确认密码
     */
    private String confirmPassword;
    /**
     * 验证码
     */
    private String verificationCode;

    // ====== 画像初始化字段（全部可选） ======

    /** 身份：undergraduate|graduate|self|career */
    private String identity;

    /** 学习方向：ai|cs|os|ds|ee|other */
    private String direction;

    /** 学习方向补充说明（选"其他"时必填） */
    private String directionDetail;

    /** 当前基础：beginner|some|course|advanced */
    private String level;

    /** 当前基础补充说明 */
    private String levelDetail;

    /** 近期目标：exam|project|skill|competition */
    private String goal;

    /** 近期目标补充说明 */
    private String goalDetail;

    /** 自由文本自我描述（最长500字符） */
    private String selfDescription;
}
