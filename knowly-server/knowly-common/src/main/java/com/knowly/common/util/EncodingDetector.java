package com.knowly.common.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 编码检测与转换工具。
 *
 * <p>用途：处理老文件的 GBK/GB2312 编码，统一转为 UTF-8。中文场景常见于扫描件、
 * 早期 Office 文档、旧系统导出的文本。
 *
 * <p><b>检测策略</b>（不依赖外部库，纯 JDK 实现）：
 * <ol>
 *   <li><b>BOM 检测</b>：UTF-8/UTF-16 BOM 头最可靠，优先判定</li>
 *   <li><b>UTF-8 严格校验</b>：UTF-8 有严格的字节模式，用 CharsetDecoder 严格模式
 *       试探解码——能完整解码即判 UTF-8（误判率极低）</li>
 *   <li><b>候选编码试探</b>：依次尝试 GBK/Big5（中文场景常见），取第一个能完整解码的</li>
 *   <li><b>兜底</b>：都失败则按 UTF-8 解（可能含替换字符）</li>
 * </ol>
 *
 * <p>局限性：纯启发式，对短文本或混合编码可能误判。生产级推荐 icu4j 或
 * juniversalchardet，但 v0.1 用此轻量版避免重依赖。
 */
public final class EncodingDetector {

    /** UTF-8 BOM */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    /** UTF-16 LE BOM */
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    /** UTF-16 BE BOM */
    private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    /** 中文场景的候选编码（UTF-8 之后的试探顺序） */
    private static final List<String> CANDIDATE_ENCODINGS = List.of("GBK", "Big5");

    private EncodingDetector() {}

    /**
     * 检测字节数组的编码。
     *
     * @param bytes 原始字节
     * @return 检测出的编码名（如 "UTF-8"/"GBK"/"UTF-16LE"）；无法确定时返回 "UTF-8"
     */
    public static String detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return StandardCharsets.UTF_8.name();

        // 1) BOM 检测
        String bomEncoding = detectBom(bytes);
        if (bomEncoding != null) return bomEncoding;

        // 2) UTF-8 严格校验（最严格，先判）
        if (isValidUtf8(bytes)) return StandardCharsets.UTF_8.name();

        // 3) 候选编码试探
        for (String candidate : CANDIDATE_ENCODINGS) {
            if (canDecode(bytes, candidate)) return candidate;
        }

        // 4) 兜底 UTF-8
        return StandardCharsets.UTF_8.name();
    }

    /**
     * 把字节数组转成 UTF-8 字符串（自动检测编码）。
     *
     * @param bytes 原始字节
     * @return UTF-8 字符串
     */
    public static String convertToUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        String encoding = detect(bytes);
        // 剥除 BOM（如果有）
        byte[] payload = stripBom(bytes);
        Charset charset = charsetForName(encoding);
        return new String(payload, charset);
    }

    /** BOM 头检测，返回对应编码或 null */
    private static String detectBom(byte[] bytes) {
        if (startsWith(bytes, UTF8_BOM)) return StandardCharsets.UTF_8.name();
        if (startsWith(bytes, UTF16LE_BOM)) return StandardCharsets.UTF_16LE.name();
        if (startsWith(bytes, UTF16BE_BOM)) return StandardCharsets.UTF_16BE.name();
        return null;
    }

    /** 剥除 BOM 头 */
    private static byte[] stripBom(byte[] bytes) {
        if (startsWith(bytes, UTF8_BOM)) return subarray(bytes, UTF8_BOM.length);
        if (startsWith(bytes, UTF16LE_BOM)) return subarray(bytes, UTF16LE_BOM.length);
        if (startsWith(bytes, UTF16BE_BOM)) return subarray(bytes, UTF16BE_BOM.length);
        return bytes;
    }

    /** UTF-8 严格校验：能完整解码且无替换字符 */
    private static boolean isValidUtf8(byte[] bytes) {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            // 进一步校验：不应有 Unicode 替换字符（说明原是 GBK 误判为 UTF-8 的边界情况）
            return decoded.toString().indexOf('\uFFFD') < 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 判断指定编码能否完整解码（严格模式） */
    private static boolean canDecode(byte[] bytes, String encoding) {
        try {
            Charset charset = Charset.forName(encoding);
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Charset charsetForName(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;   // 兜底
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] subarray(byte[] bytes, int offset) {
        byte[] result = new byte[bytes.length - offset];
        System.arraycopy(bytes, offset, result, 0, result.length);
        return result;
    }
}
