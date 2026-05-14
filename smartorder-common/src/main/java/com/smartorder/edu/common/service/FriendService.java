package com.smartorder.edu.common.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartorder.edu.common.model.entity.FriendRequest;
import com.smartorder.edu.common.model.entity.UserFriend;
import com.smartorder.edu.common.model.vo.FriendVO;


import java.util.List;

public interface FriendService extends IService<FriendRequest> {
    /**
     * 发送好友申请
     */
    void sendFriendRequest(Long senderId, Long receiverId, String requestMsg);

    /**
     * 处理（同意）好友申请
     */
    void acceptFriendRequest(Long currentUserId, Long requestId);

    /**
     * 获取当前用户的好友列表
     */
    List<UserFriend> getFriendList(Long userId);

    /**
     * 获取当前用户的待处理好友申请列表
     */
    List<FriendRequest> getPendingRequests(Long userId);

    /**
     * 删除好友 (单删)
     */
    void deleteFriend(Long currentUserId, Long friendId);

    /**
     * 拉黑好友
     */
    void blockFriend(Long currentUserId, Long friendId);


    List<FriendVO> getFriendDetailList(Long userId);

    /**
     * 设置好友备注
     * @param currentUserId 当前用户ID
     * @param friendId 好友ID
     * @param alias 备注名
     */
    void setFriendAlias(Long currentUserId, Long friendId, String alias);
}
