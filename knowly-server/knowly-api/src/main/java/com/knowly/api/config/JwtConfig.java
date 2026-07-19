package com.knowly.api.config;

import com.knowly.api.auth.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 工具 Bean 配置。独立于 WebConfig，避免循环依赖。
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil() {
        // HMAC-SHA256 要求 key >= 256 bits（32 bytes）
        return new JwtUtil("knowly-jwt-secret-key-v0.1-2026-must-be-at-least-32-bytes-long!!!");
    }
}
