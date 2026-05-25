package com.smartorder.edu.common.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import com.smartorder.edu.common.model.dto.UserDTO;
import com.smartorder.edu.common.model.entity.User;
import com.smartorder.edu.common.model.vo.UserVO;
import com.smartorder.edu.common.exception.BusinessException;
import com.smartorder.edu.common.mapper.UserMapper;
import com.smartorder.edu.common.service.IUserService;
import com.smartorder.edu.common.util.AliOSSUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.util.validation.ValidationException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.smartorder.edu.common.config.YunPanMQConfig.MQ_ROUTING_KEY_FILE_QUOTE;
import static com.smartorder.edu.common.config.YunPanMQConfig.MQ_YUN_PAN_EXCHANGE;

/**
 * 用户服务实现类
 * @author 谢光湘
 * @since 2026/2/26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {

    private final UserMapper userMapper;
    private final AliOSSUtils aliOSSUtils;
    private final String DEFAULT_AVATAR_URL = "https://xueling-platform.oss-cn-hangzhou.aliyuncs.com/d915734b-a240-4350-acb6-30b6e2d3d981.jpg";
    private final RabbitTemplate rabbitTemplate;

    @Override
    public void insertUser(User user) {
        long userId = IdUtil.getSnowflakeNextId();
        user.setId(userId);
        // 如果用户未设置用户名，则生成默认随机名
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            long count = userMapper.selectCount(null);
            long index = count + 1;
            user.setUsername("启慧" + index);
        }
        if (user.getRole() == null) {
            user.setRole(0);
        }
        if (user.getAvatarUrl() == null || user.getAvatarUrl().isBlank()) {
            user.setAvatarUrl(DEFAULT_AVATAR_URL);
        }
        userMapper.insert(user);

        // 1.创建CorrelationData
        CorrelationData cd = new CorrelationData();

        // 2.设置ConfirmCallback（替代原来的Future回调）--生产者确认机制
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.debug("发送消息成功，收到 ack!");
            } else {
                log.error("发送消息失败，收到 nack, reason : {}", cause);
            }
        });

        rabbitTemplate.convertAndSend(MQ_YUN_PAN_EXCHANGE, MQ_ROUTING_KEY_FILE_QUOTE, userId, new MessagePostProcessor() {
            @Override
            public Message postProcessMessage(Message message) throws AmqpException {
                message.getMessageProperties()
                        .setExpiration("10000");
                return message;
            }
        },cd);//开启生产者确认模式，发送消息时会等待 RabbitMQ 的确认回调

        log.info("消息发送成功！");
    }

    @Override
    public User getUserByEmailWithPassword(String email) {
        //邮箱不能为空
        if (email == null) {
            throw new ValidationException("邮箱不能为空");
        }
        //正则表达式校验
        if (!email.matches("^[a-zA-Z0-9_.-]+@[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z0-9]{2,6}$")) {
            throw new ValidationException("邮箱格式错误");
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        return userMapper.selectOne(queryWrapper);
    }

    /**
     * 根据用户手机号查用户信息
     * @param phone 手机号
     * @return User 用户实体类
     */
    @Override
    public User getUserByPhoneWithPassword(String phone) {
        //手机号不能为空
        if (phone == null) {
            throw new ValidationException("手机号不能为空");
        }
        //正则表达式校验
        if (!phone.matches("^1[3456789]\\d{9}$")) {
            throw new ValidationException("手机号格式错误");
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("phone", phone);
        return userMapper.selectOne(queryWrapper);
    }


    @Override
    public void updateUserById(UserDTO userDTO) {
        User user = new User();
        // 复制属性但不包括邮箱，以防止邮箱被意外修改
        user.setId(userDTO.getId());
        user.setUsername(userDTO.getUsername());
        user.setPhone(userDTO.getPhone());
        user.setAvatarUrl(userDTO.getAvatarUrl());
        user.setGender(userDTO.getGender());
        user.setBirthday(userDTO.getBirthday());
        user.setBio(userDTO.getBio());

        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userDTO.getId());
        if (user.getBio() != null) {
            if (user.getBio().length() > 500) {
                throw new ValidationException("个人简介不能超过500字");
            }
            updateWrapper.set("bio", user.getBio());
        }
        if (user.getBirthday() != null) {
            updateWrapper.set("birthday", user.getBirthday());
        }
        if (user.getGender() != null) {
            updateWrapper.set("gender", user.getGender());
        }
        if (user.getUsername() != null) {
            if (user.getUsername().length() > 20) {
                throw new ValidationException("用户名不能超过20字");
            }
            updateWrapper.set("username", user.getUsername());
        }
        if (user.getPhone() != null) {
            updateWrapper.set("phone", user.getPhone());
        }
        if (user.getAvatarUrl() != null) {
            updateWrapper.set("avatar_url", user.getAvatarUrl());
        }
        userMapper.update(null, updateWrapper);
    }



    @Override
    public UserVO getUserInfo(Long id) {
        //检查id是否为空
        if (id == null) {
            throw new ValidationException("用户ID不能为空");
        }
        //根据用户ID查询用户信息
        User user = userMapper.selectById(id);

        if (user != null) {
            //将用户实体类转换为用户数据传输对象
            UserVO userVO = new UserVO();
            BeanUtils.copyProperties(user, userVO);
            return userVO;
        }
        //用户不存在异常
        throw new BusinessException("用户不存在");
    }

    /**
     * 根据邮箱和手机号获取密码
     * @param email 邮箱
     * @return String 密码
     */
    @Override
    public String getPasswordByEmail(String email) {
        //检查邮箱是否为空
        if (email == null) {
            //邮箱不能为空异常
            throw new ValidationException("邮箱不能为空");
        }
        //根据邮箱查询用户密码
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("email", email);
        String password = userMapper.selectOne(queryWrapper).getPassword();
        //如果密码为空，则抛出异常
        if (password == null) {
            //邮箱或手机号错误异常
            throw new BusinessException("不存在该邮箱");
        }
        return password;
    }

    /**
     * 根据手机号获取密码
     * @param phone 手机号
     * @return String 密码
     */
    @Override
    public String getPasswordByPhone(String phone) {
        //检查手机号是否为空
        if (phone == null) {
            //手机号不能为空异常
            throw new ValidationException("手机号不能为空");
        }
        //根据手机号查询用户密码
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("phone", phone);
        String password = userMapper.selectOne(queryWrapper).getPassword();
        //如果密码为空，则抛出异常
        if (password == null) {
            //邮箱或手机号错误异常
            throw new BusinessException("不存在该手机号");
        }
        return password;
    }

    @Override
    public String uploadAvatar(Long userId, MultipartFile avatar) {
        try {
            String avatarUrl = aliOSSUtils.upload(avatar, "user/avatar/" + userId + "/");
            //更新用户头像URL
            UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("id", userId).set("avatar_url", avatarUrl);
            userMapper.update(null, updateWrapper);
            return avatarUrl;
        } catch (IOException e) {
            log.error("用户{}头像上传失败",userId,e);
            throw new BusinessException("头像上传失败,请稍后重试");
        }
    }

    @Override
    public void updatePassword(Long userId, String encodedPassword) {
        //检查用户ID是否为空
        if (userId == null) {
            //用户ID不能为空异常
            throw new ValidationException("用户ID不能为空");
        }
        //检查加密后的密码是否为空
        if (encodedPassword == null) {
            //密码不能为空异常
            throw new ValidationException("密码不能为空");
        }
        //根据用户ID更新用户密码
        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userId).set("password", encodedPassword);
        userMapper.update(null, updateWrapper);
    }

    @Override
    public void cancelUserAccount(Long userId, String verificationCode) {
        //检查用户ID是否为空
        if (userId == null) {
            //用户ID不能为空异常
            throw new ValidationException("用户ID不能为空");
        }

        //TODO 手机验证码校验

        //TODO 注销用户账号
        //逻辑删除用户
        UpdateWrapper<User> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userId).set("is_deleted", 3);
        userMapper.update(null, updateWrapper);
    }

    @Override
    public boolean isTeacherId(Long userId) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("id", userId).eq("role", 2);
        return userMapper.selectCount(queryWrapper) > 0;
    }

    @Override
    public boolean isStudentId(Long userId) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getId, userId).eq(User::getRole, 0);
        return userMapper.selectCount(queryWrapper) > 0;
    }


    @Override
    public List<UserVO> searchUsers(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 构建查询条件
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.and(wrapper ->
                wrapper.like("username", keyword)
                        .or().eq("phone", keyword)
                        .or().eq("email", keyword)
        );
        // 排除已注销的用户 (account_status = 3)
        queryWrapper.ne("account_status", 3);

        // 执行查询
        List<User> users = userMapper.selectList(queryWrapper);

        // 将 User 实体列表转换为 UserVO 列表，避免暴露密码等敏感信息
        return users.stream().map(user -> {
            UserVO userVO = new UserVO();
            BeanUtils.copyProperties(user, userVO);
            return userVO;
        }).collect(Collectors.toList());


    }


}
