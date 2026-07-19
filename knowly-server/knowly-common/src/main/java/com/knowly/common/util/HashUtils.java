package com.knowly.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具：内容哈希、documentId 派生、chunk id 生成。
 *
 * <p>设计要点：
 * <ul>
 *   <li>SHA-256 算法</li>
 *   <li>documentId 由 contentHash 派生（路径变化也稳定）</li>
 *   <li>chunk id = documentId#ordinal（文件内稳定，保证 sink 幂等）
 * </ul>
 */
public final class HashUtils {

    private HashUtils() {}

    /**
     * 计算内容的 SHA-256 哈希（十六进制字符串）。
     *
     * @param text 原始文本（已 normalize）
     * @return 64 位十六进制哈希
     */
    public static String contentHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不会抛
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 由内容哈希派生 documentId。
     * <p>格式：doc-{contentHash 前 12 位}。路径变化也认作同一文档。
     *
     * @param contentHash 内容哈希
     * @return documentId，如 "doc-ab12cd34ef56"
     */
    public static String documentId(String contentHash) {
        return "doc-" + contentHash.substring(0, 12);
    }

    /**
     * 生成 chunk id。
     * <p>格式：{documentId}#{ordinal}。文件内稳定，保证 sink 幂等写入。
     *
     * @param documentId 文档 ID
     * @param ordinal    chunk 在文档中的顺序号（从 0 开始）
     * @return chunk id，如 "doc-ab12cd34ef56#0"
     */
    public static String chunkId(String documentId, int ordinal) {
        return documentId + "#" + ordinal;
    }

    /** byte[] 转十六进制小写字符串 */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
