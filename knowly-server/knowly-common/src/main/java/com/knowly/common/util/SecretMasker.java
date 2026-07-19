package com.knowly.common.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏工具。
 *
 * <p>铁律：API key、密码、token 等绝不进日志/报告/异常/UI。
 * 本工具统一处理脱敏：日志打印、异常消息、配置展示前必须过一遍。
 */
public final class SecretMasker {

    private SecretMasker() {}

    /** 疑似 API Key / token 的正则：sk-/AKIA/ghp_/github_pat/ 等 */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            // sk- 开头（OpenAI/DashScope）
            "(sk-[a-zA-Z0-9]{8,})"
            // AWS Access Key
            + "|(AKIA[0-9A-Z]{16})"
            // GitHub token
            + "|(gh[pousr]_[A-Za-z0-9]{20,})"
            // password=xxx / api_key=xxx / token=xxx（含中文键名）
            + "|((?:password|passwd|api_?key|secret|token|密码|密钥)[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,}]{6,})",
            Pattern.CASE_INSENSITIVE
    );

    /** 脱敏后保留的前缀和后缀长度 */
    private static final int PREFIX_KEEP = 6;
    private static final int SUFFIX_KEEP = 4;

    /**
     * 脱敏字符串中的疑似密钥。返回脱敏后的安全文本。
     * <p>例：sk-abcd1234...wxyz → sk-abcd...wxyz
     *
     * @param text 原始文本（可能含密钥）
     * @return 脱敏后的文本
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = SECRET_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String full = matcher.group(0);
            // 对于 password=xxx 格式，只脱敏 value 部分（group 5 是前缀，group 6 是值）
            if (matcher.group(5) != null && matcher.group(6) != null) {
                String prefix = matcher.group(5);
                String value = matcher.group(6);
                matcher.appendReplacement(sb, prefix + maskValue(value));
            } else {
                matcher.appendReplacement(sb, maskValue(full));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 脱敏单个值。保留前 6 后 4，中间用 ... 替代。
     * <p>例：sk-abcd1234efgh5678 → sk-abcd...5678
     *
     * @param value 原始值
     * @return 脱敏后的值
     */
    public static String maskValue(String value) {
        if (value == null || value.length() <= PREFIX_KEEP + SUFFIX_KEEP) {
            return "****";
        }
        return value.substring(0, PREFIX_KEEP)
                + "..."
                + value.substring(value.length() - SUFFIX_KEEP);
    }

    /**
     * 包装配置 Map，访问值时自动脱敏（用于日志/展示）。
     * <p>注意：只影响读取展示，不影响实际配置使用。
     *
     * @param config 原始配置
     * @return 脱敏视图（新 Map，不改原始）
     */
    public static Map<String, String> wrap(Map<String, String> config) {
        Map<String, String> masked = new HashMap<>();
        config.forEach((k, v) -> {
            if (looksLikeSecretKey(k)) {
                masked.put(k, maskValue(v));
            } else {
                masked.put(k, v);
            }
        });
        return masked;
    }

    /** 判断 key 名是否像敏感字段 */
    private static boolean looksLikeSecretKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("passwd")
                || lower.contains("api_key") || lower.contains("apikey")
                || lower.contains("secret") || lower.contains("token")
                || lower.contains("密码") || lower.contains("密钥");
    }
}
