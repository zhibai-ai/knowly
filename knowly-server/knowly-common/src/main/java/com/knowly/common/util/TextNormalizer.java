package com.knowly.common.util;

/**
 * 文本规范化工具。
 *
 * <p>用途：去重前对文本做 normalize（内容哈希 + MinHash 的前置步骤）。
 * 中文场景默认开启：全角转半角、繁简统一、去多余空白。
 *
 * <p>注意：繁简统一依赖字符映射表，v0.1 用简化版映射（常见繁体字）。
 * 后续可替换为更完整的繁简转换库。
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    /** 默认 normalize 选项（中文场景：全开） */
    private static final NormalizeOptions DEFAULT_OPTIONS = new NormalizeOptions(true, true, true);

    /**
     * 默认规范化（中文场景：全角转半角 + 繁简统一 + 去多余空白）。
     *
     * @param text 原始文本
     * @return 规范化后的文本
     */
    public static String normalize(String text) {
        return normalize(text, DEFAULT_OPTIONS);
    }

    /**
     * 按选项规范化文本。
     *
     * @param text    原始文本
     * @param options 规范化选项
     * @return 规范化后的文本
     */
    public static String normalize(String text, NormalizeOptions options) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        if (options.fullwidthToHalfwidth) {
            result = fullwidthToHalfwidth(result);
        }
        if (options.traditionalToSimplified) {
            result = traditionalToSimplified(result);
        }
        if (options.trimWhitespace) {
            result = trimWhitespace(result);
        }
        return result;
    }

    /**
     * 全角转半角。
     * <p>全角 ASCII（\uFF01-\uFF5E）→ 半角（\u0021-\u007E）；全角空格 \u3000 → 半角空格。
     */
    public static String fullwidthToHalfwidth(String text) {
        if (text == null) return null;
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\u3000') {
                chars[i] = ' ';
            } else if (chars[i] >= '\uFF01' && chars[i] <= '\uFF5E') {
                chars[i] = (char) (chars[i] - 0xFEE0);
            }
        }
        return new String(chars);
    }

    /**
     * 繁体转简体。
     * <p>使用 OpenCC4j（OpenCC 的 Java 实现），覆盖全部繁简映射，支持词组级转换。
     * 如"地勢坤" → "地势坤"（词组级，非逐字）。
     */
    public static String traditionalToSimplified(String text) {
        if (text == null) return null;
        return com.github.houbb.opencc4j.util.ZhConverterUtil.toSimple(text);
    }

    /**
     * 去多余空白：多空格合一、多换行合一、去首尾空白。
     */
    public static String trimWhitespace(String text) {
        if (text == null) return null;
        // 多个空白字符合一
        String result = text.replaceAll("[ \\t]+", " ");
        // 多个换行合一（\r\n 和 \r 统一为 \n）
        result = result.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        result = result.replaceAll("\\n{3,}", "\n\n");
        return result.strip();
    }

    /**
     * 规范化选项。
     */
    public static class NormalizeOptions {
        public final boolean fullwidthToHalfwidth;
        public final boolean traditionalToSimplified;
        public final boolean trimWhitespace;

        public NormalizeOptions(boolean fullwidthToHalfwidth, boolean traditionalToSimplified, boolean trimWhitespace) {
            this.fullwidthToHalfwidth = fullwidthToHalfwidth;
            this.traditionalToSimplified = traditionalToSimplified;
            this.trimWhitespace = trimWhitespace;
        }
    }
}
