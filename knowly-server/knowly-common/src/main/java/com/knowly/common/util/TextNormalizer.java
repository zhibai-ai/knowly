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
     *
     * <p><b>只转 ASCII 字母/数字/基础标点的全角变体</b>，保留中文标点：
     * <ul>
     *   <li>全角空格 \u3000 → 半角空格</li>
     *   <li>全角 ASCII 字母数字（Ａ-Ｚａ-ｚ０-９）→ 半角</li>
     *   <li>全角基础标点（！＂＃＄％＆＇（）＊＋，－．／：；＜＝＞？＠［＼］＾＿｀｛｜｝～）→ 半角</li>
     * </ul>
     *
     * <p><b>关键修正</b>：中文标点（，。、；：？！等）虽落在全角区，但属于中文内容，
     * 不应被转成半角。否则"中医基础，阴阳五行"会变成"中医基础,阴阳五行"，破坏中文阅读体验。
     * 故只转 ASCII 范围（U+FF01！~ U+FF5E~）中真正对应 ASCII 的字符，跳过中文专用标点。
     *
     * <p>判定方式：转换后若结果是 ASCII 可见字符（\u0021~\u007E）且非中文标点冲突区，
     * 则保留转换。中文标点（U+3000-U+303F、U+FF01/FF0C/FF1A/FF1B/FF1F 等）单独白名单排除。
     */
    public static String fullwidthToHalfwidth(String text) {
        if (text == null) return null;
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '\u3000') {
                // 全角空格 → 半角空格
                chars[i] = ' ';
            } else if (c >= '\uFF01' && c <= '\uFF5E' && !isChineseFullwidthPunctuation(c)) {
                // 全角 ASCII 变体 → 半角（排除中文全角标点）
                chars[i] = (char) (c - 0xFEE0);
            }
        }
        return new String(chars);
    }

    /**
     * 判断字符是否为中文全角标点（不应转半角）。
     *
     * <p>这些字符虽在全角区（U+FF00 段），但是中文专用标点，转半角会破坏中文语义：
     * ，（U+FF0C）、。（U+3002）、、（U+3001）、；（U+FF1B）、：（U+FF1A）、
     * ？（U+FF1F）、！（U+FF01）、《》（U+300A/B）、「」『』（）等。
     */
    private static boolean isChineseFullwidthPunctuation(char c) {
        // 中文全角标点白名单（这些转半角会破坏中文阅读，必须保留）
        return c == '\uFF0C'   // ， 中文逗号
                || c == '\uFF1B'  // ； 中文分号
                || c == '\uFF1A'  // ： 中文冒号
                || c == '\uFF1F'  // ？ 中文问号
                || c == '\uFF01'  // ！ 中文叹号
                || c == '\uFF08'  // （ 中文左括号
                || c == '\uFF09'  // ） 中文右括号
                || c == '\u3001'  // 、 中文顿号
                || c == '\u3002'  // 。 中文句号
                || c == '\u3010'  // 【
                || c == '\u3011'  // 】
                || c == '\u300A'  // 《
                || c == '\u300B'  // 》
                || c == '\u300C' || c == '\u300D'  // 「」
                || c == '\u300E' || c == '\u300F'  // 『』
                || c == '\u2014'  // — 破折号
                || c == '\u2026'; // … 省略号
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
