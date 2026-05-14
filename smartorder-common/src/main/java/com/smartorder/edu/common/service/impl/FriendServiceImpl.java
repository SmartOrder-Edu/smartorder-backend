package com.smartorder.edu.common.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.smartorder.edu.common.domain.entity.FriendRequest;
import com.smartorder.edu.common.domain.entity.User;
import com.smartorder.edu.common.domain.entity.UserFriend;
import com.smartorder.edu.common.domain.vo.FriendVO;
import com.smartorder.edu.common.mapper.FriendRequestMapper;
import com.smartorder.edu.common.mapper.UserFriendMapper;
import com.smartorder.edu.common.mapper.UserMapper;
import com.smartorder.edu.common.service.FriendService;
import com.smartorder.edu.common.websocket.ChatWebSocketServer;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service // 关键：交由 Spring 容器管理
public class FriendServiceImpl extends ServiceImpl<FriendRequestMapper, FriendRequest> implements FriendService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserFriendMapper userFriendMapper;

    @Override
    public void sendFriendRequest(Long senderId, Long receiverId, String requestMsg) {
        // 1. 检查是否已经是好友
        LambdaQueryWrapper<UserFriend> friendQuery = new LambdaQueryWrapper<>();
        friendQuery.eq(UserFriend::getUserId, senderId)
                .eq(UserFriend::getFriendId, receiverId)
                .eq(UserFriend::getStatus, 1);
        if (userFriendMapper.selectCount(friendQuery) > 0) {
            throw new RuntimeException("你们已经是好友了");
        }

        // 2. 检查是否已有未处理的申请
        LambdaQueryWrapper<FriendRequest> reqQuery = new LambdaQueryWrapper<>();
        reqQuery.eq(FriendRequest::getSenderId, senderId)
                .eq(FriendRequest::getReceiverId, receiverId)
                .eq(FriendRequest::getStatus, 0); // 0-待处理
        if (this.count(reqQuery) > 0) {
            throw new RuntimeException("已发送过好友申请，请等待对方处理");
        }

        // 3. 保存申请到数据库
        FriendRequest request = new FriendRequest();
        request.setSenderId(senderId);
        request.setReceiverId(receiverId);
        request.setRequestMsg(requestMsg);
        request.setStatus(0);
        request.setCreateTime(LocalDateTime.now());
        request.setUpdateTime(LocalDateTime.now());
        this.save(request); // 继承 ServiceImpl 后，这里的 save 就可以正常调用了

        // 4. 通知接收方有新的好友申请
        ChatWebSocketServer.sendSystemMessage(receiverId, "FRIEND_REQUEST", "您收到一条新的好友申请");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptFriendRequest(Long currentUserId, Long requestId) {
        FriendRequest request = this.getById(requestId);
        if (request == null) {
            throw new RuntimeException("好友申请记录不存在");
        }
        if (!request.getReceiverId().equals(currentUserId)) {
            throw new RuntimeException("无权处理此申请");
        }
        if (request.getStatus() != 0) {
            throw new RuntimeException("该申请已被处理");
        }

        // 更新申请状态为 1 (已同意)
        request.setStatus(1);
        request.setUpdateTime(LocalDateTime.now());
        this.updateById(request);

        Long senderId = request.getSenderId();
        Long receiverId = request.getReceiverId();

        // 冗余双向写入好友关系表
        insertUserFriend(senderId, receiverId);
        insertUserFriend(receiverId, senderId);

        // 通知发起方对方已同意
        ChatWebSocketServer.sendSystemMessage(senderId, "SYSTEM", "对方已同意您的好友申请，现在可以开始聊天了");
    }

    @Override
    public List<UserFriend> getFriendList(Long userId) {
        // 查询我的好友列表 (状态为 1-正常 的数据)
        LambdaQueryWrapper<UserFriend> query = new LambdaQueryWrapper<>();
        query.eq(UserFriend::getUserId, userId)
                .eq(UserFriend::getStatus, 1)
                .orderByDesc(UserFriend::getCreateTime);
        return userFriendMapper.selectList(query);
    }

    @Override
    public List<FriendRequest> getPendingRequests(Long userId) {
        // 查询发给我的、且状态为 0 (待处理) 的好友申请
        LambdaQueryWrapper<FriendRequest> query = new LambdaQueryWrapper<>();
        query.eq(FriendRequest::getReceiverId, userId)
                .eq(FriendRequest::getStatus, 0)
                .orderByDesc(FriendRequest::getCreateTime);
        return this.list(query);
    }

    /**
     * 提取出的公共方法
     */
    private void insertUserFriend(Long userId, Long friendId) {
        LambdaQueryWrapper<UserFriend> query = new LambdaQueryWrapper<>();
        query.eq(UserFriend::getUserId, userId).eq(UserFriend::getFriendId, friendId);
        if (userFriendMapper.selectCount(query) == 0) {
            UserFriend userFriend = new UserFriend();
            userFriend.setUserId(userId);
            userFriend.setFriendId(friendId);
            userFriend.setStatus(1);
            userFriend.setCreateTime(LocalDateTime.now());
            userFriendMapper.insert(userFriend);
        }
    }
    @Override
    public void deleteFriend(Long currentUserId, Long friendId) {
        LambdaQueryWrapper<UserFriend> query = new LambdaQueryWrapper<>();
        query.eq(UserFriend::getUserId, currentUserId)
                .eq(UserFriend::getFriendId, friendId);

        UserFriend userFriend = userFriendMapper.selectOne(query);
        if (userFriend == null) {
            throw new RuntimeException("好友关系不存在");
        }

        // 状态更新为 2 (单向删除)
        userFriend.setStatus(2);
        userFriendMapper.updateById(userFriend);

        // 注意：这里不需要发 WebSocket 通知，因为删好友通常是静默的
    }

    @Override
    public void blockFriend(Long currentUserId, Long friendId) {
        LambdaQueryWrapper<UserFriend> query = new LambdaQueryWrapper<>();
        query.eq(UserFriend::getUserId, currentUserId)
                .eq(UserFriend::getFriendId, friendId);

        UserFriend userFriend = userFriendMapper.selectOne(query);
        if (userFriend == null) {
            throw new RuntimeException("好友关系不存在");
        }

        // 状态更新为 3 (拉黑)
        userFriend.setStatus(3);
        userFriendMapper.updateById(userFriend);
    }




    @Override
    public List<FriendVO> getFriendDetailList(Long userId) {
        // 1. 获取当前用户的好友关系列表 (状态为 1-正常 的数据)
        LambdaQueryWrapper<UserFriend> query = new LambdaQueryWrapper<>();
        query.eq(UserFriend::getUserId, userId)
                .eq(UserFriend::getStatus, 1)
                .orderByDesc(UserFriend::getCreateTime);
        List<UserFriend> userFriends = userFriendMapper.selectList(query);

        if (userFriends == null || userFriends.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 提取所有好友的 ID 集合
        List<Long> friendIds = userFriends.stream()
                .map(UserFriend::getFriendId)
                .collect(Collectors.toList());

        // 3. 批量查询好友的 User 基本信息 (防止在 for 循环中频繁查库)
        List<User> users = userMapper.selectBatchIds(friendIds);

        // 4. 将用户 List 转为 Map，以用户 ID 为 Key，方便后续快速匹配赋值
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        // 5. 遍历好友关系，组装最终的 FriendVO 列表
        return userFriends.stream().map(uf -> {
            FriendVO friendVO = new FriendVO();

            // 获取对应的用户基本信息
            User user = userMap.get(uf.getFriendId());
            if (user != null) {
                // 把 User 的 username, avatarUrl 拷贝到 FriendVO 中
                BeanUtils.copyProperties(user, friendVO);
            }

            // 手动填充好友关系表 (UserFriend) 中的特有字段
            friendVO.setId(uf.getFriendId()); // 确保VO的ID是好友的ID，覆盖可能存在的自身ID映射混淆
            friendVO.setFriendAlias(uf.getFriendAlias());
            friendVO.setStatus(uf.getStatus());

            return friendVO;
        }).collect(Collectors.toList());
    }

    @Override
    public void setFriendAlias(Long currentUserId, Long friendId, String alias) {
        // 1. 查询好友关系记录
        LambdaQueryWrapper<UserFriend> query = new LambdaQueryWrapper<>();
        query.eq(UserFriend::getUserId, currentUserId)
                .eq(UserFriend::getFriendId, friendId);

        UserFriend userFriend = userFriendMapper.selectOne(query);
        if (userFriend == null) {
            throw new RuntimeException("好友关系不存在");
        }

        // 2. 判断状态（可选：通常只有正常好友才能改备注，如果在黑名单里你想让他也能改，可以去掉这个判断）
        if (userFriend.getStatus() != 1) {
            throw new RuntimeException("当前非正常好友状态，无法修改备注");
        }

        // 3. 更新备注名并保存
        userFriend.setFriendAlias(alias);
        userFriendMapper.updateById(userFriend);
    }

}