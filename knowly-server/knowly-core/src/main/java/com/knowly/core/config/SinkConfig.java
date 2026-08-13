package com.knowly.core.config;

import com.knowly.common.exception.ConfigException;
import com.knowly.common.exception.ErrorCode;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 单个产出目标的配置。type 决定用哪个 SinkFactory 创建，其余键值由该 Factory 自行读取。
 *
 * <p>设计为"宽松类型"——不同 sink 参数差异大，强行抽公共字段反而僵硬。
 * 用 {@link #str(String)} / {@link #intVal(String, int)} / {@link #requiredStr(String)} 等方法访问。
 *
 * <p>所有 string 值已在 {@link ConfigLoader} 阶段经过环境变量解析（${VAR} 已被替换为实际值）。
 */
public record SinkConfig(String type, Map<String, Object> properties) {

    public SinkConfig {
        Objects.requireNonNull(type, "sink type 不能为空");
        properties = properties == null ? Map.of() : Collections.unmodifiableMap(properties);
    }

    /** 取字符串，缺失返回 null */
    public String str(String key) {
        Object v = properties.get(key);
        return v == null ? null : v.toString();
    }

    /** 取字符串，缺失返回默认值 */
    public String str(String key, String defaultValue) {
        String v = str(key);
        return v == null ? defaultValue : v;
    }

    /** 取必填字符串，缺失抛 ConfigException */
    public String requiredStr(String key) {
        String v = str(key);
        if (v == null || v.isBlank()) {
            throw new ConfigException(ErrorCode.CONFIG_002,
                    "sink[" + type + "] 缺少必填配置项: " + key,
                    "请在配置文件的 sinks 段补上 " + key);
        }
        return v;
    }

    /** 取整型，缺失或非法返回默认值 */
    public int intVal(String key, int defaultValue) {
        Object v = properties.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new ConfigException(ErrorCode.CONFIG_004,
                    "sink[" + type + "] 配置项 " + key + " 不是合法整数: " + v);
        }
    }

    /** 取布尔型，缺失返回默认值 */
    public boolean bool(String key, boolean defaultValue) {
        Object v = properties.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }
}
