package com.smartorder.edu.manage.domain.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理端前端响应适配器。
 *
 * <p>管理端页面历史上混用了两种读取方式：一类直接读 {@code res.records} / {@code res.totalUsers}
 * 这样的平铺字段，另一类沿用 mock 阶段的 {@code res.data.code} / {@code res.data.data.records}。
 * 该适配器只服务 {@code /admin/**} 接口，在不修改全局 {@code Result} 的前提下，把业务数据
 * 同时以「平铺字段 + code/data 兼容镜像」两种形态输出，前端无需改动即可兼容。</p>
 *
 * <p>约定：{@code code} 与 {@code data} 是保留键。若业务对象自带同名字段，兼容键优先，
 * 业务原值请通过其它字段名暴露，避免出现「页面读到了 200 而不是业务值」的隐性问题。</p>
 */
public final class AdminFrontendPayload {

    /** 前端 mock 阶段约定的成功码，与全局 Result 的 0 并存，二者前端都能识别 */
    private static final int FRONTEND_SUCCESS_CODE = 200;

    /** 保留键：兼容层占用的字段名 */
    private static final String KEY_CODE = "code";
    private static final String KEY_DATA = "data";

    /** 属性描述符缓存：避免每次请求都重新做一次 BeanInfo 反射扫描 */
    private static final Map<Class<?>, List<PropertyDescriptor>> DESCRIPTOR_CACHE = new ConcurrentHashMap<>();

    private AdminFrontendPayload() {
    }

    /**
     * 包装普通对象，让返回体同时支持平铺字段读取和 mock 兼容读取。
     *
     * @param payload 真实业务数据对象，可为 VO、Map 或 null
     * @return 管理端前端兼容的数据体
     */
    public static Map<String, Object> data(Object payload) {
        Map<String, Object> content = toContentMap(payload);
        Map<String, Object> root = new LinkedHashMap<>(content);
        root.put(KEY_CODE, FRONTEND_SUCCESS_CODE);
        root.put(KEY_DATA, legacyMirror(content));
        return root;
    }

    /**
     * 包装 MyBatis-Plus 分页对象，对齐前端 mock 使用的 records/total/page/size 字段。
     *
     * @param page 分页查询结果，可为 null
     * @return 管理端前端兼容的分页数据体
     */
    public static Map<String, Object> page(IPage<?> page) {
        Map<String, Object> content = new LinkedHashMap<>();
        // records 为 null 时统一返回空数组，避免前端遍历时出现 null.length 报错
        List<?> records = page == null ? null : page.getRecords();
        content.put("records", records == null ? Collections.emptyList() : records);
        content.put("total", page == null ? 0 : numeric(page.getTotal()));
        content.put("page", page == null ? 1 : numeric(page.getCurrent()));
        content.put("size", page == null ? 10 : numeric(page.getSize()));
        return data(content);
    }

    /**
     * 包装无业务数据的操作成功响应，供重试、作废、批量创建等命令接口复用。
     *
     * @return 管理端前端兼容的空数据体
     */
    public static Map<String, Object> empty() {
        return data(Collections.emptyMap());
    }

    /**
     * 构造 mock 兼容镜像层，支撑旧页面中的 {@code res.data.code} 与 {@code res.data.data.xxx} 访问。
     *
     * @param content 已平铺的业务字段
     * @return 镜像数据体
     */
    private static Map<String, Object> legacyMirror(Map<String, Object> content) {
        Map<String, Object> mirror = new LinkedHashMap<>(content);
        mirror.put(KEY_CODE, FRONTEND_SUCCESS_CODE);
        mirror.put(KEY_DATA, new LinkedHashMap<>(content));
        return mirror;
    }

    /**
     * 分页元数据统一输出为 JSON 数字。
     *
     * <p>全局 Jackson 配置把所有 Long 序列化成字符串（防止前端 ID 精度丢失），
     * 但 {@code total/page/size} 是分页组件直接参与计算的元数据，输出字符串会触发
     * el-pagination 的 total 类型校验告警，因此这里显式收窄为 Integer，超出范围时降级为 BigDecimal。</p>
     *
     * @param value 原始长整型分页元数据
     * @return 可被 Jackson 序列化为 JSON 数字的值
     */
    private static Number numeric(long value) {
        if (value <= Integer.MAX_VALUE && value >= Integer.MIN_VALUE) {
            return (int) value;
        }
        return BigDecimal.valueOf(value);
    }

    /**
     * 将 VO 或 Map 平铺成字段 Map，避免引入 JSON 序列化对 LocalDateTime 的额外要求。
     *
     * @param payload 业务对象
     * @return 字段名到字段值的映射，保持声明顺序
     */
    private static Map<String, Object> toContentMap(Object payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        if (payload instanceof Map<?, ?> sourceMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            sourceMap.forEach((key, value) -> {
                if (key != null) {
                    result.put(String.valueOf(key), value);
                }
            });
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            for (PropertyDescriptor descriptor : descriptorsOf(payload.getClass())) {
                if (descriptor.getReadMethod() != null) {
                    result.put(descriptor.getName(), descriptor.getReadMethod().invoke(payload));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("管理端响应对象转换失败: " + payload.getClass().getName(), e);
        }
        return result;
    }

    /**
     * 获取并缓存可读属性描述符，只保留业务字段（排除 Object 自带属性）。
     *
     * @param type 业务对象类型
     * @return 可读属性描述符列表
     */
    private static List<PropertyDescriptor> descriptorsOf(Class<?> type) {
        return DESCRIPTOR_CACHE.computeIfAbsent(type, key -> {
            try {
                return List.of(Introspector.getBeanInfo(key, Object.class).getPropertyDescriptors());
            } catch (Exception e) {
                throw new IllegalStateException("管理端响应对象解析失败: " + key.getName(), e);
            }
        });
    }
}
