package com.smartorder.edu.agent.util;

import java.util.regex.Pattern;

/**
 * 前端生成的对外标识（conversation_uuid / message_uuid / draft_id）格式校验。
 *
 * <p>只做“36 位带连字符的 UUID”这一层宽松校验：既接受 {@code crypto.randomUUID()}
 * 生成的 v4，也接受历史上 {@code UUID.randomUUID()} 写入库的存量值；
 * 不接受空串、纯数字、带空格或超长值——这些一定是客户端拼错了字段。</p>
 */
public final class UuidUtils {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private UuidUtils() {
    }

    public static boolean isValid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
