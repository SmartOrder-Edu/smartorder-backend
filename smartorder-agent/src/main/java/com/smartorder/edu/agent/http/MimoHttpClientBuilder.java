package com.smartorder.edu.agent.http;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * HttpClientBuilder 包装器，自动将构建的 HttpClient 包装为
 * {@link MimoThinkingModeDisableHttpClient}，禁用 MIMO thinking mode。
 */
@Slf4j
public class MimoHttpClientBuilder implements HttpClientBuilder {

    private final HttpClientBuilder delegate;

    public MimoHttpClientBuilder() {
        SpringRestClientBuilder springBuilder = new SpringRestClientBuilder();
        springBuilder.restClientBuilder(RestClient.builder());
        springBuilder.createDefaultStreamingRequestExecutor(true);
        this.delegate = springBuilder;
        log.info("[MimoHttpClientBuilder] 已启用 MIMO thinking mode 禁用拦截器");
    }

    @Override
    public HttpClient build() {
        HttpClient defaultClient = delegate.build();
        return new MimoThinkingModeDisableHttpClient(defaultClient);
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }
}
