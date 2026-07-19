package com.knowly.api.common;

import java.util.Map;

/**
 * 统一 API 响应格式。
 */
public record ApiResponse(int code, Object data, String message) {
    public static ApiResponse ok(Object data) { return new ApiResponse(0, data, null); }
    public static ApiResponse ok(Object data, String msg) { return new ApiResponse(0, data, msg); }
    public static ApiResponse error(String code, String msg) { return new ApiResponse(-1, null, msg); }
    public Map<String, Object> toMap() {
        return Map.of("code", code, "data", data != null ? data : "", "message", message != null ? message : "");
    }
}
