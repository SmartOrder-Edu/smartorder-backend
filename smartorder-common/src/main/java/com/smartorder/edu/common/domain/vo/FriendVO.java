package com.smartorder.edu.common.domain.vo;

import lombok.Data;

/**
 * 好友详细信息视图对象
 * 包含好友的用户基本信息以及我们之间的关系属性
 */
@Data
public class FriendVO {

    // ================= 用户基本信息 (对应 User 表) =================
    /**
     * 用户ID (好友的ID)
     */
    private Long id;
    /**
     * 用户名
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
     * 头像URL
     */
    private String avatarUrl;
    /**
     * 性别
     */
    private Integer gender;
    /**
     * 生日
     */
    private String birthday;
    /**
     * 个人简介
     */
    private String bio;
    /**
     * 角色
     */
    private Integer role;
    /**
     * 教学领域，教师用户特有属性
     */
    private String teachingField;
    /**
     * vip等级
     */
    private Integer vipLevel;
    /**
     * vip开始时间
     */
    private String vipStartTime;
    /**
     * vip到期时间
     */
    private String vipExpireTime;

    // ================= 好友关系属性 (对应 UserFriend 表) =================
    /**
     * 好友备注名
     */
    private String friendAlias;

    /**
     * 好友状态: 1-正常, 2-单向删除, 3-拉黑
     */
    private Integer status;
}