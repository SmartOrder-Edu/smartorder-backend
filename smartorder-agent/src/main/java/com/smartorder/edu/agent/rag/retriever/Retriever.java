package com.smartorder.edu.agent.rag.retriever;

import com.smartorder.edu.agent.rag.model.RagChunk;
import com.smartorder.edu.agent.rag.model.RetrievalContext;

import java.util.List;

/**
 * 检索器接口
 * 所有检索器必须实现此接口
 */
public interface Retriever {

    /**
     * 获取检索器类型
     */
    RetrieverType type();

    /**
     * 执行检索
     *
     * @param context 检索上下文
     * @return 检索到的文档块列表
     */
    List<RagChunk> retrieve(RetrievalContext context);

    /**
     * 检索器是否可用
     */
    default boolean isAvailable() {
        return true;
    }
}
