package com.smartorder.edu.common.service;


import com.smartorder.edu.common.domain.dto.UserDTO;
import com.smartorder.edu.common.domain.entity.User;
import com.smartorder.edu.common.domain.vo.UserVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 用户服务接口
 * @author 谢光湘
 * @since 2026/2/26
 */
public interface IUserService {
    /**
     * 用户注册
     * @param user 用户数据传输对象
     */
    void insertUser(User user);
    /**
     * 根据用户邮箱查用户信息(包含密码)
     * @param email 邮箱
     * @return User 用户实体类
     */
    User getUserByEmailWithPassword(String email);

    /**
     * 根据用户手机号查用户信息(包含密码)
     * @param phone 手机号
     * @return User 用户实体类
     */
    User getUserByPhoneWithPassword(String phone);

    /**
     * 根据用户ID更新用户信息
     * @param userDTO 用户数据传输对象
     */
    void updateUserById(UserDTO userDTO);

    /**
     * 根据用户ID获取用户信息
     * @param id 用户ID
     * @return UserDTO 用户数据传输对象
     */
    UserVO getUserInfo(Long id);

    /**
     * 根据用户email查询密码
     * @param email 邮箱
     * @return String 密码
     */
    String getPasswordByEmail(String email);

    /**
     * 根据用户phone查询密码
     * @param phone 手机号
     * @return String 密码
     */
    String getPasswordByPhone(String phone);

    /**
     * 上传用户头像
     * @param userId 用户ID
     * @param avatar 头像文件
     * @return String 头像URL
     */
    String uploadAvatar(Long userId, MultipartFile avatar);

    /**
     * 更新用户密码
     * @param userId 用户ID
     * @param encodedPassword 加密后的密码
     */
    void updatePassword(Long userId, String encodedPassword);

    /**
     * 注销用户(发起注销 → 手机验证码 → 执行注销)
     * @param userId 用户ID
     * @param verificationCode 验证码
     */
    void cancelUserAccount(Long userId, String verificationCode);

    /**
     * 检查是否为教师ID
     */
    boolean isTeacherId(Long userId);

    /**
     * 检查是否为学生ID
     */
    boolean isStudentId(Long userId);


    /**
     * 根据关键词搜索用户（支持用户名模糊搜索，手机号/邮箱精确搜索）
     * @param keyword 搜索关键词
     * @return List<UserVO> 用户视图对象列表
     */
    List<UserVO> searchUsers(String keyword);
}