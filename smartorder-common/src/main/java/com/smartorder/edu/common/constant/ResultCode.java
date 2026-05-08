package com.smartorder.edu.common.constant;

import lombok.Getter;

/**
 * 统一的结果码枚举类
 * @author 谢光湘
 * @since 2026/2/26
 */
@Getter
public enum ResultCode {
    TEST(111,"测试枚举"),
    BUSINESS_ERROR(500,"业务异常"), VALIDATION_ERROR(501,"验证异常"),
    SYSTEM_ERROR(600,"系统异常"), DUPLICATE_KEY_ERROR(601,"数据库唯一冲突异常"), UNAUTHORIZED(401,"未登录"), EMAIL_SEND_TOO_FREQUENTLY(602,"请不要频繁发送验证码"),
    FORBIDDEN(403,"权限不足"), NOT_FOUND(404, "资源未找到"),


    // ========== 新增多模态解析相关错误码 ==========
    FILE_NOT_FOUND(700, "文件不存在或不是有效文件"),
    UNSUPPORTED_FILE_TYPE(701, "不支持的文件类型"),
    PDF_ENCRYPTED_ERROR(702, "PDF文件已加密，无法解析"),
    AUDIO_SIZE_EXCEED_LIMIT(703, "音频文件超过5MB限制，无法转写"),
    ASR_TRANSCRIBE_ERROR(704, "音频转写失败"),
    VIDEO_PARSE_ERROR(705, "视频解析失败"),
    TEMP_FILE_CLEAN_ERROR(706, "临时文件清理失败"),


    // ========== 新增分片上传相关错误码 ==========
    UPLOAD_TASK_NOT_FOUND(800, "上传任务不存在"),
    UPDATE_CHUNK_ERROR(801, "更新分片信息失败"),
    MERGE_CHUNK_ERROR(802, "合并分片失败");





    private final Integer code;
    private final String msg;

    ResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

}
