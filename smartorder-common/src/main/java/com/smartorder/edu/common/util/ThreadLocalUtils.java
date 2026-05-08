package com.smartorder.edu.common.util;

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
}

