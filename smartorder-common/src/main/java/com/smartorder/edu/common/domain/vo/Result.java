package com.smartorder.edu.common.domain.vo;


import com.smartorder.edu.common.constant.ResultCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应对象
 * @author 谢光湘
 * @since 2026/2/26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T>{
    private Integer code;
    private String msg;
    private T data;

    /**
     * 成功响应
     * @param data 响应数据
     * @return 成功响应对象
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(0,null,data);
    }

    /**
     * 成功响应
     * @param msg 自定义成功信息
     * @return 响应对象
     * @param <T> 响应数据类型
     */
    public static <T> Result<T> success(String msg) {
        return new Result<>(0, msg, null);
    }

    /**
     * 成功响应
     * @param data 响应数据
     * @param msg 自定义成功信息
     * @return 成功响应对象
     */
    public static <T> Result<T> success(T data, String msg) {
        return new Result<>(0, msg, data);
    }
    /**
     * 失败响应
     * @param resultCode 结果码枚举
     * @return 失败响应对象
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        Integer code = resultCode.getCode();
        String msg = resultCode.getMsg();
        return new Result<>(code, msg, null);
    }
    /**
     * 失败响应
     * @param resultCode 结果码枚举
     * @param msg 自定义错误信息
     * @return 失败响应对象
     */
    public static <T> Result<T> fail(ResultCode resultCode, String msg) {
        Integer code = resultCode.getCode();
        return new Result<>(code, msg, null);
    }
}
