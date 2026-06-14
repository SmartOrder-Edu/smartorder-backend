package com.smartorder.edu.agent.rag.retriever;

/**
 * 检索器类型枚举
 */
public enum RetrieverType {

    /**
     * 简单检索：向量 + 关键词混合检索
     */
    SIMPLE("simple", "简单检索", 1),

    /**
     * 中等检索：组合检索（向量 + 图）
     */
    MEDIUM("medium", "中等检索", 2),

    /**
     * 复杂检索：图 RAG 检索（多跳推理 + 子图提取）
     */
    COMPLEX("complex", "复杂检索", 3);

    private final String code;
    private final String description;
    private final int level;

    RetrieverType(String code, String description, int level) {
        this.code = code;
        this.description = description;
        this.level = level;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public int getLevel() {
        return level;
    }

    /**
     * 根据代码获取枚举
     */
    public static RetrieverType fromCode(String code) {
        for (RetrieverType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return SIMPLE;
    }

    /**
     * 获取降级类型
     */
    public RetrieverType degrade() {
        return switch (this) {
            case COMPLEX -> MEDIUM;
            case MEDIUM -> SIMPLE;
            case SIMPLE -> null; // 已经是最低级
        };
    }
}
