package com.smartorder.edu.student.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 讯飞虚拟人 WebSocket 鉴权 URL 生成工具
 */
public class XfAvatarAuthUtil {

    /**
     * 根据 基础URL、APIKey、APISecret 拼装带有 HMAC 签名的 WSS URL
     */
    public static String assembleRequestUrl(String requestUrl, String apiKey, String apiSecret) {
        URL url = null;
        // 转换WebSocket的URL，ws转为http，wss转为https，用于计算签名
        String httpRequestUrl = requestUrl.replace("ws://", "http://").replace("wss://", "https://");
        try {
            url = new URL(httpRequestUrl);
            // 设置时间格式并设置UTC时区
            SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            String date = format.format(new Date());
            String host = url.getHost();

            // 构建签名字符串
            StringBuilder builder = new StringBuilder("host: ").append(host).append("\n").
                    append("date: ").append(date).append("\n").
                    append("GET").append(" ").append(url.getPath()).append(" HTTP/1.1");

            // 生成 HMAC SHA-256 签名
            Mac mac = Mac.getInstance("hmacsha256");
            SecretKeySpec spec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "hmacsha256");
            mac.init(spec);
            byte[] hexDigits = mac.doFinal(builder.toString().getBytes(StandardCharsets.UTF_8));
            String sha = Base64.getEncoder().encodeToString(hexDigits);

            // 生成授权头信息
            String authorization = String.format("hmac username=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"",
                    apiKey, "hmac-sha256", "host date request-line", sha);

            // 将授权信息编码为 Base64
            String authBase = Base64.getEncoder().encodeToString(authorization.getBytes(StandardCharsets.UTF_8));

            // 构建最终带有参数的 WSS 连接地址
            return String.format("%s?authorization=%s&host=%s&date=%s",
                    requestUrl,
                    URLEncoder.encode(authBase, "UTF-8"),
                    URLEncoder.encode(host, "UTF-8"),
                    URLEncoder.encode(date, "UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException("拼装讯飞鉴权 URL 失败: " + e.getMessage());
        }
    }
}