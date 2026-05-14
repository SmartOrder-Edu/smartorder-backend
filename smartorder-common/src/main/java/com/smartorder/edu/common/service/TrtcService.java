package com.smartorder.edu.common.service;


import com.smartorder.edu.common.util.TLSSigAPIv2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TrtcService {

    @Value("${tencent.trtc.sdk-app-id}")
    private long sdkAppId;

    @Value("${tencent.trtc.secret-key}")
    private String secretKey;

    @Value("${tencent.trtc.expire-time:86400}")
    private long expireTime;

    /**
     * 生成基础的 UserSig (适用于绝大多数不需要超级严格进房控制的场景)
     */
    public String generateUserSig(String userId) {
        log.info("为用户 [{}] 生成 TRTC UserSig", userId);
        TLSSigAPIv2 api = new TLSSigAPIv2(sdkAppId, secretKey);
        // 调用你提供的工具类方法
        return api.genUserSig(userId, expireTime);
    }

    /**
     * (可选) 如果你开启了高级权限控制，可以封装带有房间号和权限位的鉴权
     */
    public String generatePrivateMapKey(String userId, String roomId, long privilegeMap) {
        TLSSigAPIv2 api = new TLSSigAPIv2(sdkAppId, secretKey);
        return api.genPrivateMapKeyWithStringRoomID(userId, expireTime, roomId, privilegeMap);
    }


    public Object getSdkAppId() {
        return this.sdkAppId;
    }
}