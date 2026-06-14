package com.smartorder.edu.agent.rag.router;

import com.smartorder.edu.agent.rag.config.RagConfig;
import com.smartorder.edu.agent.rag.model.QueryAnalysisResult;
import com.smartorder.edu.agent.rag.model.RetrievalRouteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认检索路由器
 * 根据查询分析结果和用户指定模式进行路由决策
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRetrievalRouter implements RetrievalRouter {

    private final RagConfig ragConfig;

    @Override
    public RetrievalRouteResult route(QueryAnalysisResult analysisResult, String requestedMode) {
        log.info("[DefaultRetrievalRouter] 开始路由决策，requestedMode={}, suggestedMode={}",
                requestedMode, analysisResult.getSuggestedMode());

        // 如果用户指定了非 auto 模式，直接使用
        if (requestedMode != null && !"auto".equalsIgnoreCase(requestedMode)) {
            return buildExplicitResult(requestedMode, analysisResult);
        }

        // 自动模式：基于查询分析结果决策
        return autoRoute(analysisResult);
    }

    /**
     * 自动路由
     */
    private RetrievalRouteResult autoRoute(QueryAnalysisResult analysisResult) {
        String suggestedMode = analysisResult.getSuggestedMode();
        double confidence = analysisResult.getConfidence();

        // 如果置信度较低，降级到简单模式
        if (confidence < 0.5) {
            log.info("[DefaultRetrievalRouter] 置信度过低({})，降级到简单模式", confidence);
            return RetrievalRouteResult.builder()
                    .retrievalMode("simple")
                    .confidence(confidence)
                    .reason("分析置信度不足，使用简单检索")
                    .degraded(true)
                    .originalMode(suggestedMode)
                    .build();
        }

        // 根据建议模式路由
        String reason = buildReason(analysisResult);

        log.info("[DefaultRetrievalRouter] 自动路由决策: mode={}, reason={}", suggestedMode, reason);

        return RetrievalRouteResult.builder()
                .retrievalMode(suggestedMode)
                .confidence(confidence)
                .reason(reason)
                .degraded(false)
                .build();
    }

    /**
     * 处理用户显式指定的模式
     */
    private RetrievalRouteResult buildExplicitResult(String requestedMode,
                                                      QueryAnalysisResult analysisResult) {
        String mode = requestedMode.toLowerCase();

        // 验证模式有效性
        if (!isValidMode(mode)) {
            log.warn("[DefaultRetrievalRouter] 无效的检索模式: {}，使用自动路由", mode);
            return autoRoute(analysisResult);
        }

        log.info("[DefaultRetrievalRouter] 使用用户指定模式: {}", mode);

        return RetrievalRouteResult.builder()
                .retrievalMode(mode)
                .confidence(1.0)
                .reason("用户指定检索模式: " + mode)
                .degraded(false)
                .build();
    }

    /**
     * 构建路由原因
     */
    private String buildReason(QueryAnalysisResult analysisResult) {
        StringBuilder sb = new StringBuilder();
        sb.append("查询分析结果: ");

        if (analysisResult.isSimple()) {
            sb.append("简单事实查询，使用简单检索");
        } else if (analysisResult.isComplex()) {
            sb.append("复杂推理查询，使用图RAG检索");
        } else {
            sb.append("中等复杂度查询，使用组合检索");
        }

        if (analysisResult.getEntities() != null && !analysisResult.getEntities().isEmpty()) {
            sb.append("；识别实体: ").append(String.join(", ", analysisResult.getEntities()));
        }

        return sb.toString();
    }

    /**
     * 验证模式是否有效
     */
    private boolean isValidMode(String mode) {
        return "simple".equals(mode) || "medium".equals(mode) || "complex".equals(mode);
    }
}
