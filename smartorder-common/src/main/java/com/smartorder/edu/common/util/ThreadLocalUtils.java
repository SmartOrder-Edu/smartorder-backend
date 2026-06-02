package com.smartorder.edu.common.util;

import io.jsonwebtoken.Claims;

public class ThreadLocalUtils {
    private static final ThreadLocal<Object> THREAD_LOCAL = new ThreadLocal<>();

    @SuppressWarnings("unchecked")
    public static <T> T get(){
        return (T) THREAD_LOCAL.get();
    }

    public static  void set(Object value){
        THREAD_LOCAL.set(value);
    }

    public static void remove(){
        THREAD_LOCAL.remove();
    }

    public static Long getCurrentUserId() throws Exception {
        Claims claims = ThreadLocalUtils.get();
        if (claims == null) {
            throw new Exception("未登录");
        }
        Object userId = claims.get("userId");
        if (userId == null) {
            throw new Exception("用户信息缺失");
        }
        return (Long) userId;
    }
}

