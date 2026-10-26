package com.smartorder.edu.agent.util;

import java.math.BigInteger;

/** Redis Stream 恢复游标的统一校验和比较工具。 */
public final class AgentStreamCursor {
    private AgentStreamCursor() { }

    /** 有效 Last-Event-ID 优先于 URL 中的旧快照游标。 */
    public static String choose(String lastEventId, String after) {
        String selected = lastEventId != null && !lastEventId.isBlank() ? lastEventId : after;
        if (selected == null || selected.isBlank()) return "0-0";
        parts(selected);
        return selected;
    }

    /** 按毫秒和序号两个数值分量排序。 */
    public static int compare(String left, String right) {
        BigInteger[] a = parts(left);
        BigInteger[] b = parts(right);
        int ms = a[0].compareTo(b[0]);
        return ms != 0 ? ms : a[1].compareTo(b[1]);
    }

    /** 限制两个分量长度，阻止畸形请求耗费大量解析资源。 */
    private static BigInteger[] parts(String value) {
        if (value == null || !value.matches("[0-9]{1,20}-[0-9]{1,20}")) {
            throw new IllegalArgumentException("非法 Redis Stream 游标");
        }
        String[] split = value.split("-", 2);
        return new BigInteger[]{new BigInteger(split[0]), new BigInteger(split[1])};
    }
}
