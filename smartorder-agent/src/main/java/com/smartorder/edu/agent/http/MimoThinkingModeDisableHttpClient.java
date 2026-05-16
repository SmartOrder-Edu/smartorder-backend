package com.smartorder.edu.agent.http;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 包装 HttpClient，对 MIMO API 请求添加 "thinking": {"type": "disabled"}，
 * 防止 MIMO 开启 thinking mode 生成 reasoning_content，
 * 因为 LangChain4j 1.0.0-beta3 的 AiMessage 不支持 reasoningContent 字段，
 * 导致多轮 ReAct 循环中 reasoning_content 丢失，MIMO API 报 400 错误。
 */
@Slf4j
@RequiredArgsConstructor
public class MimoThinkingModeDisableHttpClient implements HttpClient {

    private final HttpClient delegate;

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        HttpRequest modifiedRequest = injectThinkingDisabled(request);
        return delegate.execute(modifiedRequest);
    }

    @Override
    public void execute(HttpRequest request,
                        ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        HttpRequest modifiedRequest = injectThinkingDisabled(request);
        delegate.execute(modifiedRequest, parser, listener);
    }

    /**
     * 在请求 JSON body 中注入 "thinking": {"type": "disabled"}
     */
    private HttpRequest injectThinkingDisabled(HttpRequest request) {
        String body = request.body();
        if (body == null || body.isBlank()) {
            return request;
        }

        try {
            JSONObject json = JSON.parseObject(body);
            // 只对 chat/completions 请求注入
            if (json.containsKey("messages")) {
                json.put("thinking", JSON.parseObject("{\"type\": \"disabled\"}"));
                String modifiedBody = json.toJSONString();
                log.debug("[MimoHttpClient] 已注入 thinking disabled");
                return HttpRequest.builder()
                        .method(request.method())
                        .url(request.url())
                        .headers(request.headers())
                        .body(modifiedBody)
                        .build();
            }
        } catch (Exception e) {
            log.warn("[MimoHttpClient] 注入 thinking disabled 失败，使用原始请求: {}", e.getMessage());
        }

        return request;
    }
}
