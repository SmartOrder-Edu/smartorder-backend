# RAG 系统使用指南

## 概述

RAG（Retrieval-Augmented Generation）系统是一个可扩展的知识库检索增强生成框架，支持用户通过文件ID集合和知识库ID集合进行精确检索。

## 核心特性

### 1. 三级检索策略

- **简单检索（Simple）**：向量 + 关键词混合检索
- **中等检索（Medium）**：组合检索（向量 + 图RAG），Round-Robin 交替
- **复杂检索（Complex）**：图RAG检索（多跳推理 + 子图提取）

### 2. 智能路由决策

- **自动模式**：基于 LLM 四维分析（复杂度/关系/推理/实体）自动选择检索策略
- **手动模式**：用户可指定 `simple`/`medium`/`complex` 检索模式

### 3. 降级机制

- 复杂检索失败 → 降级到中等检索
- 中等检索失败 → 降级到简单检索
- 简单检索失败 → 触发告警

### 4. RAG 评估

- **上下文相关性**：检索内容与查询的匹配度
- **可信度**：检索内容的准确性
- **答案相关性**：检索内容能否回答查询

## 使用方式

### 1. Agent 工具调用

```java
// 通过 RagTool 进行检索
RagRetrieveRequest request = new RagRetrieveRequest();
request.setQuery("什么是机器学习？");
request.setFileIds(List.of(1L, 2L, 3L));
request.setKnowledgeBaseIds(List.of(100L));
request.setRetrievalMode("auto");
request.setTopK(10);
```

### 2. 请求参数

| 参数 | 类型 | 说明 |
|------|------|------|
| query | String | 检索查询语句 |
| fileIds | List<Long> | 文件ID集合（可选） |
| knowledgeBaseIds | List<Long> | 知识库ID集合（可选） |
| retrievalMode | String | 检索模式：auto/simple/medium/complex |
| topK | Integer | 返回结果数量，默认10 |

### 3. 响应结构

```json
{
    "success": true,
    "contextText": "【RAG 检索结果】...",
    "usedRetrievalMode": "medium",
    "chunkCount": 5,
    "degraded": false,
    "evaluationScore": 0.85,
    "evaluationPassed": true
}
```

## 配置说明

### application.yml 配置

```yaml
agent:
  rag:
    enabled: true
    default-top-k: 10
    max-top-k: 20
    default-retrieval-mode: auto
    analysis:
      llm-enabled: true
      simple-threshold: 0.3
      complex-threshold: 0.7
    retrieval:
      vector-weight: 0.7
      keyword-weight: 0.3
      max-graph-hops: 3
      graph-timeout-ms: 5000
    evaluation:
      enabled: true
      pass-threshold: 0.6
    degrade:
      enabled: true
      complex-to-medium: true
      medium-to-simple: true
      alert-on-simple-failure: true
```

## 扩展指南

### 1. 添加新检索器

```java
@Component
public class CustomRetriever implements Retriever {
    @Override
    public RetrieverType type() {
        return RetrieverType.CUSTOM;
    }
    
    @Override
    public List<RagChunk> retrieve(RetrievalContext context) {
        // 自定义检索逻辑
    }
}
```

### 2. 自定义评估器

```java
@Component
public class CustomEvaluator implements RagEvaluator {
    @Override
    public RagEvaluationResult evaluate(String query, List<RagChunk> chunks) {
        // 自定义评估逻辑
    }
}
```

### 3. 自定义路由器

```java
@Component
public class CustomRouter implements RetrievalRouter {
    @Override
    public RetrievalRouteResult route(QueryAnalysisResult analysis, String mode) {
        // 自定义路由逻辑
    }
}
```

## 架构图

```
用户请求 (fileIds + knowledgeBaseIds + query)
                    │
                    ▼
            ┌───────────────┐
            │  RagTool      │  ← Agent 工具系统入口
            └───────┬───────┘
                    │
                    ▼
            ┌───────────────┐
            │ RagOrchestrator│  ← RAG 编排器
            └───────┬───────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │健康检查  │ │查询分析  │ │检索路由  │
  └──────────┘ └──────────┘ └────┬─────┘
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼             ▼
              ┌──────────┐ ┌──────────┐ ┌──────────┐
              │简单检索  │ │中等检索  │ │复杂检索  │
              └────┬─────┘ └────┬─────┘ └────┬─────┘
                   │            │             │
                   └────────────┼─────────────┘
                                ▼
                        ┌──────────────┐
                        │ RRF 重排融合 │
                        └──────┬───────┘
                               ▼
                        ┌──────────────┐
                        │ RAG 评估机制 │
                        └──────────────┘
```
