package com.smartorder.edu.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

public class JwtTokenUtils {


    public static final String ACCESS_TOKEN_SECRET = "xueling-access-secret";
    /**
     * Access Token密钥和过期时间(30分钟)
     */
    public static final long ACCESS_TOKEN_EXPIRE = 30 * 60 * 1000;


    public static final String REFRESH_TOKEN_SECRET = "xueling-refresh-secret";
    /**
     * Refresh Token密钥和过期时间(7天)
     */
    public static final long REFRESH_TOKEN_EXPIRE = 7 * 24 * 60 * 60 * 1000;


    /**
     * 生成Access Token
     */
    public static String generateAccessToken(Map<String, Object> claims) throws IllegalArgumentException, ExpiredJwtException {
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRE))
                .signWith(generateKey(ACCESS_TOKEN_SECRET))
                .compact();
    }

    /**
     * 生成Refresh Token
     */
    public static String generateRefreshToken(Map<String, Object> claims) throws IllegalArgumentException, ExpiredJwtException {
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + REFRESH_TOKEN_EXPIRE))
                .signWith(generateKey(REFRESH_TOKEN_SECRET))
                .compact();
    }

    // 生成安全的Key对象（推荐）
    public static SecretKey generateKey(String secret) {
        // 确保密钥长度符合要求（HS256至少32字节）
        byte[] keyBytes = secret.getBytes();
        if (keyBytes.length < 32) {
            // 如果密钥太短，进行填充
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
//TODO 检查代码

    /**
     * 验证Token并获取claims
     */
    public static Claims getClaimsFromToken(String token, String secret) {
        SecretKey key = generateKey(secret);
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)  // 注意方法名变化
                .getPayload();  // 不再是getBody()
    }

    /**
     * 检查Token是否过期
     */
    public static boolean isTokenExpired(String token, String secret) {
        try {
            Claims claims = getClaimsFromToken(token, secret);
            Date expiration = claims.getExpiration();
            return expiration.before(new Date());
        } catch (ExpiredJwtException e) {
            // 明确捕获过期异常
            return true;
        } catch (Exception e) {
            // 其他异常不直接视为过期，而是抛出具体异常
            throw new IllegalArgumentException("token验证失败: " + e.getMessage());
        }
    }
}
