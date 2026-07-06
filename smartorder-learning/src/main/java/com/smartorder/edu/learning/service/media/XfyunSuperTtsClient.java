package com.smartorder.edu.learning.service.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
@RequiredArgsConstructor
public class XfyunSuperTtsClient {

    private final ObjectMapper objectMapper;
    private final XfyunSuperTtsProperties properties;

    public CompletableFuture<TtsSynthesisResult> synthesize(String speechText) {
        if (!properties.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("讯飞超拟人 TTS 未启用"));
        }
        if (isBlank(properties.getAppId()) || !hasValidAuthConfig()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "讯飞超拟人 TTS 鉴权配置不完整：需要 appId + apiPassword，或 appId + apiKey + apiSecret"));
        }
        if (isBlank(speechText)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("待合成文本不能为空"));
        }

        CompletableFuture<TtsSynthesisResult> resultFuture = new CompletableFuture<>();
        ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
        StringBuilder phonemeBuffer = new StringBuilder();
        AtomicReference<String> sidRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder messageBuffer = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
                try {
                    webSocket.sendText(buildRequestJson(speechText), true);
                } catch (Exception e) {
                    completeExceptionallyOnce(resultFuture, completed, e);
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "request build failed");
                }
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                messageBuffer.append(data);
                if (!last) {
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);
                try {
                    handleMessage(message, audioBuffer, phonemeBuffer, sidRef, resultFuture, completed, webSocket);
                } catch (Exception e) {
                    completeExceptionallyOnce(resultFuture, completed, e);
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "parse error");
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                completeExceptionallyOnce(resultFuture, completed, enrichConnectionError(error));
            }
        };

        WebSocket.Builder builder = client.newWebSocketBuilder();
        URI websocketUri;
        try {
            websocketUri = buildWebsocketUri(builder);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        builder.buildAsync(websocketUri, listener)
                .exceptionally(error -> {
                    completeExceptionallyOnce(resultFuture, completed, enrichConnectionError(error));
                    return null;
                });

        return resultFuture.orTimeout(
                properties.getTimeoutSeconds() == null ? 90 : properties.getTimeoutSeconds(),
                TimeUnit.SECONDS
        );
    }

    private boolean hasValidAuthConfig() {
        return !isBlank(properties.getApiPassword())
                || hasHmacAuthConfig();
    }

    private URI buildWebsocketUri(WebSocket.Builder builder) throws Exception {
        if (hasHmacAuthConfig()) {
            return URI.create(buildHmacAuthUrl());
        }
        builder.header("x-api-key", properties.getApiPassword());
        return URI.create(properties.getWebsocketUrl());
    }

    private boolean hasHmacAuthConfig() {
        return !isBlank(properties.getApiKey()) && !isBlank(properties.getApiSecret());
    }

    private String authMode() {
        return hasHmacAuthConfig() ? "hmac" : "apiPassword";
    }

    private Throwable enrichConnectionError(Throwable error) {
        Throwable root = unwrap(error);
        if (root instanceof WebSocketHandshakeException handshakeException) {
            Integer status = handshakeException.getResponse() == null
                    ? null
                    : handshakeException.getResponse().statusCode();
            String host = URI.create(properties.getWebsocketUrl()).getHost();
            return new IllegalStateException("讯飞 TTS WebSocket 握手失败"
                    + (status == null ? "" : "，status=" + status)
                    + "，authMode=" + authMode()
                    + "，host=" + host, handshakeException);
        }
        return root;
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String buildHmacAuthUrl() throws Exception {
        URI uri = URI.create(properties.getWebsocketUrl());
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
            path = path + "?" + uri.getRawQuery();
        }
        String date = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .format(ZonedDateTime.now(ZoneOffset.UTC));
        String signatureOrigin = "host: " + host + "\n"
                + "date: " + date + "\n"
                + "GET " + path + " HTTP/1.1";
        String signature = hmacSha256Base64(signatureOrigin, properties.getApiSecret());
        String authorizationOrigin = "api_key=\"" + properties.getApiKey()
                + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\""
                + signature + "\"";
        String authorization = Base64.getEncoder().encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));
        String baseUrl = properties.getWebsocketUrl();
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator
                + "authorization=" + urlEncode(authorization)
                + "&date=" + urlEncode(date)
                + "&host=" + urlEncode(host);
    }

    private String hmacSha256Base64(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void handleMessage(String message,
                               ByteArrayOutputStream audioBuffer,
                               StringBuilder phonemeBuffer,
                               AtomicReference<String> sidRef,
                               CompletableFuture<TtsSynthesisResult> resultFuture,
                               AtomicBoolean completed,
                               WebSocket webSocket) throws Exception {
        JsonNode root = objectMapper.readTree(message);
        JsonNode header = root.path("header");
        int code = header.path("code").asInt(-1);
        String responseMessage = header.path("message").asText("");
        String sid = header.path("sid").asText("");
        if (!sid.isBlank()) {
            sidRef.set(sid);
        }
        if (code != 0) {
            completeExceptionallyOnce(resultFuture, completed,
                    new IllegalStateException("讯飞 TTS 调用失败，code=" + code + ", message=" + responseMessage));
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "provider error");
            return;
        }

        JsonNode payload = root.path("payload");
        appendAudio(payload, audioBuffer);
        appendPhoneme(payload, phonemeBuffer);

        int audioStatus = payload.path("audio").path("status").asInt(-1);
        int headerStatus = header.path("status").asInt(-1);
        if (audioStatus == 2 || headerStatus == 2) {
            if (completed.compareAndSet(false, true)) {
                resultFuture.complete(new TtsSynthesisResult(
                        audioBuffer.toByteArray(),
                        phonemeBuffer.toString(),
                        sidRef.get()
                ));
            }
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "finished");
        }
    }

    private String buildRequestJson(String speechText) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("app_id", properties.getAppId());
        header.put("status", 2);

        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("encoding", "lame");
        audio.put("sample_rate", 24000);
        audio.put("channels", 1);
        audio.put("bit_depth", 16);
        audio.put("frame_size", 0);

        Map<String, Object> tts = new LinkedHashMap<>();
        tts.put("vcn", defaultText(properties.getVoice(), "x5_lingfeiyi_flow"));
        tts.put("speed", defaultInt(properties.getSpeed(), 50));
        tts.put("volume", defaultInt(properties.getVolume(), 50));
        tts.put("pitch", defaultInt(properties.getPitch(), 50));
        tts.put("bgs", 0);
        tts.put("reg", 0);
        tts.put("rdn", 0);
        tts.put("rhy", 1);
        tts.put("audio", audio);

        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("tts", tts);

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("encoding", "utf8");
        text.put("compress", "raw");
        text.put("format", "plain");
        text.put("status", 2);
        text.put("seq", 0);
        text.put("text", Base64.getEncoder().encodeToString(speechText.getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("header", header);
        body.put("parameter", parameter);
        body.put("payload", payload);
        return objectMapper.writeValueAsString(body);
    }

    private void appendAudio(JsonNode payload, ByteArrayOutputStream audioBuffer) throws Exception {
        String audioBase64 = payload.path("audio").path("audio").asText("");
        if (!audioBase64.isBlank()) {
            audioBuffer.write(Base64.getDecoder().decode(audioBase64));
        }
    }

    private void appendPhoneme(JsonNode payload, StringBuilder phonemeBuffer) {
        String pybufBase64 = payload.path("pybuf").path("text").asText("");
        if (!pybufBase64.isBlank()) {
            phonemeBuffer.append(new String(Base64.getDecoder().decode(pybufBase64), StandardCharsets.UTF_8));
        }
    }

    private void completeExceptionallyOnce(CompletableFuture<?> future, AtomicBoolean completed, Throwable throwable) {
        if (completed.compareAndSet(false, true)) {
            future.completeExceptionally(throwable);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private int defaultInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
