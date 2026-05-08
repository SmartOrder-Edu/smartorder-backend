package com.smartorder.edu.common.exception;

/**
 * 基础异常类，所有自定义异常都应继承自该类
 * @author 谢光湘
 * @since 2026/2/26
 */
public class BaseException extends RuntimeException {
    public BaseException(String message) {
        super(message);
    }
}

