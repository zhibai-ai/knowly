package com.knowly.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link EncodingDetector} 单元测试。覆盖 BOM 检测、UTF-8/GBK 识别、转换。
 */
class EncodingDetectorTest {

    @Test
    void should_detect_utf8_without_bom() {
        byte[] bytes = "中医基础理论".getBytes(StandardCharsets.UTF_8);
        assertThat(EncodingDetector.detect(bytes)).isEqualTo("UTF-8");
    }

    @Test
    void should_detect_utf8_with_bom() {
        // 带 UTF-8 BOM
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "测试".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(content, 0, withBom, bom.length, content.length);

        assertThat(EncodingDetector.detect(withBom)).isEqualTo("UTF-8");
    }

    @Test
    void should_detect_gbk() {
        byte[] bytes = "中医基础理论阴阳五行".getBytes(Charset.forName("GBK"));
        assertThat(EncodingDetector.detect(bytes)).isEqualTo("GBK");
    }

    @Test
    void should_convert_gbk_to_utf8() {
        String original = "天行健，君子以自强不息";
        byte[] gbkBytes = original.getBytes(Charset.forName("GBK"));

        String converted = EncodingDetector.convertToUtf8(gbkBytes);

        assertThat(converted).isEqualTo(original);
    }

    @Test
    void should_convert_utf8_to_utf8_unchanged() {
        String original = "已经是 UTF-8 的文本";
        byte[] utf8Bytes = original.getBytes(StandardCharsets.UTF_8);

        String converted = EncodingDetector.convertToUtf8(utf8Bytes);

        assertThat(converted).isEqualTo(original);
    }

    @Test
    void should_strip_bom_when_converting() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "带BOM的文本".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(content, 0, withBom, bom.length, content.length);

        String converted = EncodingDetector.convertToUtf8(withBom);

        // BOM 被剥除，内容正确
        assertThat(converted).doesNotStartWith("\uFEFF");
        assertThat(converted).isEqualTo("带BOM的文本");
    }

    @Test
    void should_handle_empty_and_null() {
        assertThat(EncodingDetector.detect(null)).isEqualTo("UTF-8");
        assertThat(EncodingDetector.detect(new byte[0])).isEqualTo("UTF-8");
        assertThat(EncodingDetector.convertToUtf8(null)).isEmpty();
        assertThat(EncodingDetector.convertToUtf8(new byte[0])).isEmpty();
    }

    @Test
    void should_handle_ascii() {
        // 纯 ASCII 是合法 UTF-8，应判 UTF-8
        byte[] bytes = "Hello World 123".getBytes(StandardCharsets.US_ASCII);
        assertThat(EncodingDetector.detect(bytes)).isEqualTo("UTF-8");
    }

    @Test
    void should_not_misidentify_gbk_as_utf8() {
        // 中文标点在 GBK 下可能误判为 UTF-8 的边界场景。
        // 验证："中医方剂学" 的 GBK 编码不应被判为 UTF-8（GBK 字节序列不是合法 UTF-8）
        byte[] gbkBytes = "中医方剂学桂枝汤麻黄汤".getBytes(Charset.forName("GBK"));
        String detected = EncodingDetector.detect(gbkBytes);
        // 应判为 GBK，而非误判 UTF-8（若误判，转回字符串会乱码）
        assertThat(detected).isEqualTo("GBK");
        // 双向校验：转回字符串应等于原文
        assertThat(new String(gbkBytes, Charset.forName(detected)))
                .isEqualTo("中医方剂学桂枝汤麻黄汤");
    }
}
