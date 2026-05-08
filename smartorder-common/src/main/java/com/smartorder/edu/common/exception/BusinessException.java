package com.smartorder.edu.common.exception;

/**
 * 业务异常类，用于表示业务级别的异常。
 * 用于处理也就是“预期内”的逻辑阻断，比如“余额不足”、“用户已存在”。
 * @author 谢光湘
 * @since 2026/2/26
 */
public class BusinessException extends BaseException {
    public BusinessException(String message) {
        super(message);
    }
    public BusinessException(String message, Object... args) {
        super(String.format(message, args));
    }
}
