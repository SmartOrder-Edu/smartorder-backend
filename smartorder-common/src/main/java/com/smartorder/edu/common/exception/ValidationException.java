package com.smartorder.edu.common.exception;

/**
 * 验证异常类，用于表示验证失败的异常。
 * 用于手动参数校验失败，比如“参数为空”、“参数格式错误”。
 * @author 谢光湘
 * @since 2026/2/26
 */
public class ValidationException extends BaseException {
    public ValidationException(String message) {
        super(message);
    }
}
