package com.smartorder.edu.common.controller;


import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.model.entity.FriendRequest;
import com.smartorder.edu.common.model.vo.FriendVO;
import com.smartorder.edu.common.model.vo.Result;
import com.smartorder.edu.common.model.vo.UserVO;
import com.smartorder.edu.common.service.FriendService;
import com.smartorder.edu.common.service.IUserService;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import com.smartorder.edu.common.websocket.ChatWebSocketServer;
import io.jsonwebtoken.Claims;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/friend")
@Slf4j
public class FriendController {

    @Autowired
    private FriendService friendService;


    @Autowired
    private IUserService userService;

    /**
     * 发送好友申请
     */
    @PostMapping("/request")
    public Result<String> sendFriendRequest(
            @RequestParam("receiverId") String receiverId,
            @RequestParam(value = "requestMsg", defaultValue = "你好，我想加你为好友") String requestMsg) {

        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        if (currentUserId.equals(receiverId)) {
            return Result.fail(ResultCode.BUSINESS_ERROR, "不能添加自己为好友");
        }


        Long  receiverId1 = Long.valueOf(receiverId.toString());
        friendService.sendFriendRequest(currentUserId, receiverId1, requestMsg);
        return Result.success("好友申请已发送");
    }


    /**
     * 处理（同意）好友申请
     */
    @PutMapping("/accept/{requestId}")
    public Result<String> acceptFriendRequest(@PathVariable("requestId") Long requestId) {

        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        friendService.acceptFriendRequest(currentUserId, requestId);
        return Result.success("已添加对方为好友");
    }

//    /**
//     * 获取当前用户的好友列表
//     */
//    @GetMapping("/list")
//    public Result<List<UserFriend>> getFriendList() {
//        Claims claims = ThreadLocalUtils.get();
//        Long currentUserId = claims.get("userId", Long.class);
//
//        List<UserFriend> friendList = friendService.getFriendList(currentUserId);
//        return Result.success(friendList);
//    }



    /**
     * 获取当前用户的好友详细信息列表（包含基本信息、备注名和状态）
     */
    @GetMapping("/detail-list")
    @Operation(summary = "获取好友详细列表", description = "获取好友的基本信息及备注名、状态")
    public Result<List<FriendVO>> getFriendDetailList() {
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        List<FriendVO> friendDetailList = friendService.getFriendDetailList(currentUserId);
        return Result.success(friendDetailList);
    }


    /**
     * 获取当前用户的待处理好友申请列表
     */
    @GetMapping("/pending-requests")
    public Result<List<FriendRequest>> getPendingRequests() {
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        List<FriendRequest> pendingRequests = friendService.getPendingRequests(currentUserId);
        return Result.success(pendingRequests);
    }

    /**
     * 删除好友
     */
    @DeleteMapping("/{friendId}")
    public Result<String> deleteFriend(@PathVariable("friendId") Long friendId) {
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        friendService.deleteFriend(currentUserId, friendId);
        return Result.success("删除好友成功");
    }

    /**
     * 拉黑好友
     */
    @PutMapping("/block/{friendId}")
    public Result<String> blockFriend(@PathVariable("friendId") Long friendId) {
        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        friendService.blockFriend(currentUserId, friendId);
        return Result.success("已将该用户加入黑名单");
    }


    /**
     * 搜索用户
     * @param keyword 搜索关键词（用户名、手机号或邮箱）
     * @return Result<List<UserVO>> 包含匹配的用户列表
     */
    @GetMapping("/search")
    @Operation(summary = "搜索好友", description = "根据用户名模糊搜索，或根据手机号/邮箱精确搜索")
    public Result<List<UserVO>> searchUsers(@RequestParam String keyword) {
        log.info("执行好友搜索，关键词: {}", keyword);
        List<UserVO> userList = userService.searchUsers(keyword);
        return Result.success(userList);
    }

    /**
     * 设置好友备注
     */
    @PutMapping("/alias/{friendId}")
    @Operation(summary = "设置好友备注", description = "为指定好友设置或修改备注名称")
    public Result<String> setFriendAlias(
            @PathVariable("friendId") Long friendId,
            @RequestParam(value = "alias", required = false) String alias) { // required = false 允许清空备注

        Claims claims = ThreadLocalUtils.get();
        Long currentUserId = claims.get("userId", Long.class);

        // 如果前端传 null 或空字符串，可以视为清空备注
        friendService.setFriendAlias(currentUserId, friendId, alias);

        return Result.success("备注设置成功");
    }


    /**
     * 批量获取好友在线状态
     * @param friendIds 好友 ID 列表
     * @return Map<Long, Boolean> 集合，Key 为好友 ID，Value 为是否在线
     */
    @PostMapping("/online-status/batch")
    @Operation(summary = "批量获取好友在线状态", description = "传入好友 ID 列表，一次性返回所有传入好友的在线状态")
    public Result<Map<Long, Boolean>> getBatchFriendOnlineStatus(@RequestBody List<Long> friendIds) {

        // 1. 如果前端传了空列表，直接返回空集合
        if (friendIds == null || friendIds.isEmpty()) {
            return Result.success(new HashMap<>());
        }

        // (可选) 安全校验：如果你想做得严谨一点，可以在这里获取当前 userId，
        // 然后去数据库比对传入的 friendIds 是否都在他的好友列表里，防止恶意遍历查询。
        // Claims claims = ThreadLocalUtils.get();
        // Long currentUserId = claims.get("userId", Long.class);

        // 2. 遍历 ID 列表，从 WebSocket 服务的内存缓存中读取状态
        Map<Long, Boolean> statusMap = new HashMap<>();
        for (Long friendId : friendIds) {
            boolean isOnline = ChatWebSocketServer.isUserOnline(friendId);
            statusMap.put(friendId, isOnline);
        }

        // 3. 返回结果给前端
        return Result.success(statusMap);
    }

}