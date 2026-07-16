package com.smartorder.edu.student.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Xfyun avatar WebSocket authorization URL builder.
 */
public class XfAvatarAuthUtil {

    public static String assembleRequestUrl(String requestUrl, String apiKey, String apiSecret) {
        // 转换WebSocket的URL，ws转为http，wss转为https，用于计算签名
        String httpRequestUrl = requestUrl.replace("ws://", "http://").replace("wss://", "https://");
        try {
            // 设置时间格式并设置UTC时区
            URI uri = URI.create(httpRequestUrl);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                path = path + "?" + uri.getRawQuery();
            }
            String date = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .format(ZonedDateTime.now(ZoneOffset.UTC));

            // 构建签名字符串
            // 生成 HMAC SHA-256 签名
            String signatureOrigin = "host: " + host + "\n"
                    + "date: " + date + "\n"
                    + "GET " + path + " HTTP/1.1";
            String signature = hmacSha256Base64(signatureOrigin, apiSecret);

            // 生成授权头信息
            String authorizationOrigin = "api_key=\"" + apiKey
                    + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\""
                    + signature + "\"";
            String authorization = Base64.getEncoder()
                    .encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

            String separator = requestUrl.contains("?") ? "&" : "?";
            return requestUrl + separator
                    + "authorization=" + urlEncode(authorization)
                    + "&host=" + urlEncode(host)
                    + "&date=" + urlEncode(date);
        } catch (Exception e) {
            throw new RuntimeException("Failed to assemble Xfyun avatar authorization URL: " + e.getMessage(), e);
        }
    }

    private static String hmacSha256Base64(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
