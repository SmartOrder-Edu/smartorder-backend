package com.smartorder.edu.common.config;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LangChain4j HTTP client backed by JDK HttpClient.
 *
 * <p>Do not use Spring RestClient here: Spring Boot auto-selects Jetty when it is
 * on the classpath, and Jetty's default 30s idle timeout aborts long LLM calls.
 */
public class JdkLangChain4jHttpClientBuilder implements HttpClientBuilder {

    private Duration connectTimeout;
    private Duration readTimeout;

    public static JdkLangChain4jHttpClientBuilder withDefaults() {
        return new JdkLangChain4jHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(120));
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public JdkLangChain4jHttpClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public JdkLangChain4jHttpClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    @Override
    public HttpClient build() {
        java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder();
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }
        return new JdkLangChain4jHttpClient(builder.build(), readTimeout);
    }

    private static class JdkLangChain4jHttpClient implements HttpClient {

        private static final ExecutorService STREAMING_EXECUTOR = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "langchain4j-jdk-http-streaming");
            thread.setDaemon(true);
            return thread;
        });

        private final java.net.http.HttpClient delegate;
        private final Duration readTimeout;

        private JdkLangChain4jHttpClient(java.net.http.HttpClient delegate, Duration readTimeout) {
            this.delegate = delegate;
            this.readTimeout = readTimeout;
        }

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            java.net.http.HttpRequest jdkRequest = toJdkRequest(request).build();
            try {
                HttpResponse<String> response = delegate.send(
                        jdkRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new HttpException(response.statusCode(), response.body());
                }
                return SuccessfulHttpResponse.builder()
                        .statusCode(response.statusCode())
                        .headers(response.headers().map())
                        .body(response.body())
                        .build();
            } catch (IOException e) {
                throw new RuntimeException("I/O error during LLM HTTP request", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during LLM HTTP request", e);
            }
        }

        @Override
        public void execute(
                HttpRequest request,
                ServerSentEventParser parser,
                ServerSentEventListener listener
        ) {
            STREAMING_EXECUTOR.execute(() -> executeStreaming(request, parser, listener));
        }

        private void executeStreaming(
                HttpRequest request,
                ServerSentEventParser parser,
                ServerSentEventListener listener
        ) {
            java.net.http.HttpRequest jdkRequest = toJdkRequest(request).build();
            try {
                HttpResponse<InputStream> response = delegate.send(
                        jdkRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String body;
                    try (InputStream inputStream = response.body()) {
                        body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    listener.onError(new HttpException(response.statusCode(), body));
                    return;
                }

                listener.onOpen(SuccessfulHttpResponse.builder()
                        .statusCode(response.statusCode())
                        .headers(response.headers().map())
                        .build());
                try (InputStream inputStream = response.body()) {
                    parser.parse(inputStream, listener);
                }
                listener.onClose();
            } catch (Exception e) {
                listener.onError(e);
            }
        }

        private java.net.http.HttpRequest.Builder toJdkRequest(HttpRequest request) {
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(request.url()));
            if (readTimeout != null) {
                builder.timeout(readTimeout);
            }
            for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                for (String value : header.getValue()) {
                    builder.header(header.getKey(), value);
                }
            }
            String body = request.body();
            builder.method(
                    request.method().name(),
                    body == null
                            ? java.net.http.HttpRequest.BodyPublishers.noBody()
                            : java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            );
            return builder;
        }
    }
}
