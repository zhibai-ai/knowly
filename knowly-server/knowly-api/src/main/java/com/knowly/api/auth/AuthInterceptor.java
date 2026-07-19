package com.knowly.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 认证拦截器。除 /api/auth/login 外所有请求需携带 JWT。
 * 支持 Authorization header 和 URL query 参数两种方式（EventSource 不支持自定义 header）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtUtil jwtUtil;

    public AuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();
        if (path.equals("/api/auth/login")) return true;

        // 先从 Authorization header 读 token
        String token = null;
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7);
        }
        // header 里没有，尝试从 URL query 参数读（EventSource 不支持自定义 header）
        if (token == null) {
            token = request.getParameter("token");
        }

        if (token == null || !jwtUtil.validateToken(token)) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"SEC_004\",\"message\":\"未登录或token无效\"}");
            return false;
        }
        return true;
    }
}
