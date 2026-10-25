package com.smartorder.edu.common.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 旧版应用异常。
 *
 * @deprecated 本类是从外部脚手架复制进来的遗留实现（{@link #toString()} 里仍残留
 * {@code cn.bugstack.types.exception} 包名），全仓已无任何抛出或捕获点。
 * 项目统一异常体系为 {@link BusinessException}（业务失败）/ {@link SystemException}
 * （系统故障）/ {@link ValidationException}（参数校验），由 {@link GlobalExceptionHandler}
 * 统一转成 {@code Result}。新代码请使用上述异常，本类可在确认后删除。
 */
@Deprecated(since = "2.0", forRemoval = false)
@EqualsAndHashCode(callSuper = true)
@Data
public class AppException extends RuntimeException {

    private static final long serialVersionUID = 5317680961212299217L;

    /** 异常码 */
    private String code;

    /** 异常信息 */
    private String info;

    public AppException(String code) {
        this.code = code;
    }

    public AppException(String code, Throwable cause) {
        this.code = code;
        super.initCause(cause);
    }

    public AppException(String code, String message) {
        this.code = code;
        this.info = message;
    }

    public AppException(String code, String message, Throwable cause) {
        this.code = code;
        this.info = message;
        super.initCause(cause);
    }

    @Override
    public String toString() {
        return "cn.bugstack.types.exception.AppException{" +
                "code='" + code + '\'' +
                ", info='" + info + '\'' +
                '}';
    }

}
