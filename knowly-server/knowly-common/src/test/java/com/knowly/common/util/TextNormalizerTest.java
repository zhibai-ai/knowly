package com.knowly.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowly.common.util.TextNormalizer.NormalizeOptions;
import org.junit.jupiter.api.Test;

/**
 * {@link TextNormalizer} 单元测试。覆盖全角/繁简/空白规范化、选项组合、边界。
 */
class TextNormalizerTest {

    @Test
    void should_convert_fullwidth_to_halfwidth() {
        assertThat(TextNormalizer.fullwidthToHalfwidth("ＡＢＣ１２３"))
                .isEqualTo("ABC123");
        assertThat(TextNormalizer.fullwidthToHalfwidth("ＡＢＣ１２３"))
                .doesNotContain("Ａ");
    }

    @Test
    void should_convert_fullwidth_space_to_halfwidth() {
        // 全角空格 \u3000 → 半角空格
        assertThat(TextNormalizer.fullwidthToHalfwidth("a\u3000b"))
                .isEqualTo("a b");
    }

    @Test
    void should_keep_halfwidth_unchanged() {
        assertThat(TextNormalizer.fullwidthToHalfwidth("abc123")).isEqualTo("abc123");
    }

    @Test
    void should_convert_traditional_to_simplified() {
        assertThat(TextNormalizer.traditionalToSimplified("天行健君子以自強不息"))
                .contains("自强")
                .doesNotContain("強");
    }

    @Test
    void should_convert_phrase_level_traditional() {
        // 词组级转换：OpenCC4j 对常见繁体词组的转换
        // 注：台湾特有用词（如「軟體」）OpenCC 默认映射为「软体」而非「软件」，
        // 这里用通用的繁体字验证转换生效即可
        String result = TextNormalizer.traditionalToSimplified("電腦設備");
        assertThat(result).contains("电脑").contains("设");   // 電腦→电脑，設→设
        assertThat(result).doesNotContain("電").doesNotContain("腦");
    }

    @Test
    void should_collapse_multiple_whitespaces() {
        assertThat(TextNormalizer.trimWhitespace("a    b\t\tc"))
                .isEqualTo("a b c");
    }

    @Test
    void should_collapse_multiple_newlines() {
        assertThat(TextNormalizer.trimWhitespace("a\n\n\n\nb"))
                .isEqualTo("a\n\nb");
    }

    @Test
    void should_normalize_crlf_to_lf() {
        assertThat(TextNormalizer.trimWhitespace("a\r\nb\rc"))
                .isEqualTo("a\nb\nc");
    }

    @Test
    void should_apply_all_normalizations_by_default() {
        // 繁体 + 全角 + 多空白 一次处理
        String result = TextNormalizer.normalize("　天行健　君子以自強不息　");
        assertThat(result).doesNotContain("　");          // 全角空格已转
        assertThat(result).contains("自强");               // 繁简统一
        assertThat(result).doesNotStartWith(" ");          // 首尾空白已去
    }

    @Test
    void should_respect_options_disabled() {
        NormalizeOptions none = new NormalizeOptions(false, false, false);
        String text = "ＡＢＣ 自強 ";
        assertThat(TextNormalizer.normalize(text, none)).isEqualTo(text);
    }

    @Test
    void should_only_fullwidth_when_only_it_enabled() {
        NormalizeOptions onlyFw = new NormalizeOptions(true, false, false);
        // 全角转了，繁体保留
        String result = TextNormalizer.normalize("Ａ自強", onlyFw);
        assertThat(result).contains("A");
        assertThat(result).contains("自強");
    }

    @Test
    void should_handle_null_and_empty() {
        assertThat(TextNormalizer.normalize(null)).isNull();
        assertThat(TextNormalizer.normalize("")).isEmpty();
        assertThat(TextNormalizer.fullwidthToHalfwidth(null)).isNull();
    }

    @Test
    void should_make_same_content_produce_same_hash_after_normalize() {
        // 去重的关键：繁简/全角差异 normalize 后应一致
        String a = TextNormalizer.normalize("天行健君子以自強不息");
        String b = TextNormalizer.normalize("天行健君子以自强不息");
        assertThat(HashUtils.contentHash(a)).isEqualTo(HashUtils.contentHash(b));
    }

    @Test
    void should_preserve_chinese_punctuation_not_convert_to_halfwidth() {
        // 中文标点不应被全角转半角（否则破坏中文阅读体验）
        // 这是中文场景的关键修正：，。、；：？！等必须保留
        String result = TextNormalizer.fullwidthToHalfwidth("中医基础，阴阳五行。");
        assertThat(result).contains("，")   // 中文逗号保留
                .contains("。");           // 中文句号保留
        assertThat(result).doesNotContain(",")  // 不应出现半角逗号
                .doesNotContain(". ");      // 不应出现半角句号
    }

    @Test
    void should_preserve_chinese_brackets_and_quotes() {
        // 中文括号、书名号、引号都应保留
        String result = TextNormalizer.fullwidthToHalfwidth("据《茶经》记载，（神农）尝百草");
        assertThat(result).contains("《").contains("》")   // 书名号
                .contains("（").contains("）");            // 中文括号
    }

    @Test
    void should_convert_fullwidth_alphanumeric_but_keep_chinese_punct() {
        // 混合场景：全角字母数字应转，中文标点保留
        String result = TextNormalizer.fullwidthToHalfwidth("温度ＡＢＣ１２３，浓度４５６。");
        assertThat(result).contains("ABC123")       // 全角字母数字转了
                .contains("456")                    // 全角数字转了
                .contains("，")                      // 中文逗号保留
                .contains("。");                     // 中文句号保留
    }
}
