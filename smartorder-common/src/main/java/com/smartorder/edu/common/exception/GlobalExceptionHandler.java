package com.smartorder.edu.common.exception;


import com.smartorder.edu.common.constant.ResultCode;
import com.smartorder.edu.common.domain.vo.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局异常处理类，用于处理应用程序中抛出的异常
 * @author 谢光湘
 * @since 2026/2/26
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理资源未找到异常
     * @param e 资源未找到异常
     * @return 错误信息
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<String> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("资源未找到: {}", e.getResourcePath());
        return Result.fail(ResultCode.NOT_FOUND, e.getMessage());
    }

    /**
     * 处理数据库唯一冲突异常
     * @param e 重复键异常对象
     * @return 错误信息
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<String> handleDuplicateKeyException(DuplicateKeyException e){
        log.error("数据库唯一冲突异常",e);
        String errorMessage = e.getMessage();
        String friendlyMsg = "数据已存在";
        //正则匹配：从异常信息中提取 值、字段名
        Pattern pattern = Pattern.compile("Duplicate entry '(.*?)' for key '(.*?)'");
        Matcher matcher = pattern.matcher(errorMessage);
        if (matcher.find()){
            String duplicateValue = matcher.group(1);   //冲突的值
            String keyName = matcher.group(2);  //冲突的字段名

            if(keyName.contains("username")){
                log.info("用户名冲突");
                friendlyMsg = String.format("用户名 %s 已被使用", duplicateValue);
            }else if (keyName.contains("email")){
                log.info("邮箱冲突");
                friendlyMsg = String.format("邮箱 %s 已注册", duplicateValue);
            }else if (keyName.contains("phone")){
                log.info("手机号冲突");
                friendlyMsg = String.format("手机号 %s 已绑定账号", duplicateValue);

            }
        }
        return Result.fail(ResultCode.DUPLICATE_KEY_ERROR, friendlyMsg);
    }



    /**
     * 处理验证异常
     * @param e 验证异常对象
     * @return 错误信息
     */
    @ExceptionHandler(ValidationException.class)
    public Result<String> handleValidationException(ValidationException e) {
        log.error("验证异常: {}", e.getMessage());
        return Result.fail(ResultCode.VALIDATION_ERROR, e.getMessage());
    }

    /**
     * 处理业务异常
     * @param e 业务异常对象
     * @return 错误信息
     */
    @ExceptionHandler(BusinessException.class)
    public Result<String> handleBusinessException(BusinessException e) {
        log.error("业务异常: {}", e.getMessage());
        return Result.fail(ResultCode.BUSINESS_ERROR, e.getMessage());
    }

    /**
     * 处理系统异常
     * @param e 系统异常对象
     * @return 错误信息
     */
    @ExceptionHandler(SystemException.class)
    public Result<String> handleSystemException(SystemException e) {
        log.error("系统异常: {}", e.getMessage());
        return Result.fail(ResultCode.SYSTEM_ERROR, e.getMessage());
    }

    /**
     * 处理权限不足异常
     * @param e 权限不足异常对象
     * @return 错误信息
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Result<String> handleAccessDeniedException(AccessDeniedException e) {
        log.error("权限不足: {}", e.getMessage());
        return Result.fail(ResultCode.FORBIDDEN);
    }


    /**
     * 处理所有未捕获的异常
     * @param e 抛出的异常
     * @return 错误信息
     */
    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        // 在这里可以添加日志记录等操作
        log.error("未处理的异常: {}", e.getMessage(), e);
        return Result.fail(ResultCode.SYSTEM_ERROR, e.getMessage());
    }


//    /**
//     * 忽略客户端主动断开连接引发的异常，避免与 SSE 响应类型冲突
//     */
//    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
//    public void handleClientAbortException(Exception ex)
//    {
//        log.warn(
//                "客户端主动断开了 SSE 连接，操作已取消: {}"
//                , ex.getMessage());
//        // 直接返回 void，千万不要返回 Result 对象！
//    }
}
