package com.smartorder.edu.common.interceptor;

import com.smartorder.edu.common.util.JwtTokenUtils;
import com.smartorder.edu.common.util.ThreadLocalUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 预处理回调方法，在控制器处理请求之前调用
     * @param request HTTP请求对象
     * @param response HTTP响应对象
     * @param handler 处理请求的处理器对象
     * @return 如果返回true，则继续处理请求；如果返回false，则中断请求处理流程
     */
    @Override
    public boolean preHandle(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler) {
        log.info("IP: {} 正在尝试访问 {}", request.getRemoteAddr(), request.getRequestURI());
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.trim().isEmpty()) {
            String urlToken = request.getParameter("token");
            if (urlToken != null && !urlToken.trim().isEmpty()) {
                authorization = "Bearer " + urlToken;
            }
        }
        try{
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new RuntimeException("无效的Token");
            }
            String token = authorization.substring(7).trim();
            Claims claims = JwtTokenUtils.getClaimsFromToken(token, JwtTokenUtils.ACCESS_TOKEN_SECRET);
            ThreadLocalUtils.set(claims);

            // 解析角色并设置到Spring Security上下文
            Integer role = claims.get("role", Integer.class);
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            if (role != null) {
                if (role == 0) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_STUDENT"));
                } else if (role == 1) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                }
            }
            log.info("用户{}---角色: {}", claims.get("userId"), authorities);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(claims.get("userId"), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            return true;
        }catch (Exception e){
            log.error("JWT令牌解析失败---未登录", e);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterCompletion(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler, Exception ex) {
        ThreadLocalUtils.remove();
        SecurityContextHolder.clearContext();
    }

}
