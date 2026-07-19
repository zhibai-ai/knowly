package com.knowly.api.auth;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JwtUtil jwtUtil;
    private final AdminAuthService authService;

    public AuthController(JwtUtil jwtUtil, AdminAuthService authService) {
        this.jwtUtil = jwtUtil;
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (!authService.verify(username, password)) {
            return Map.of("code", "SEC_004", "message", "用户名或密码错误");
        }
        String token = jwtUtil.generateToken(username);
        return Map.of("code", 0, "data", Map.of("token", token, "username", username));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Map.of("code", 0, "message", "已登出");
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader("Authorization") String auth) {
        String token = auth.substring(7);
        String username = jwtUtil.getUsername(token);
        return Map.of("code", 0, "data", Map.of("username", username, "role", "ADMIN"));
    }
}
