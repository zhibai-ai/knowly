package com.knowly.core.model;

import com.knowly.common.enums.ParseStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 原始文档（摄取层产出）。
 *
 * @param id          documentId，由 contentHash 派生
 * @param sourcePath  源文件路径
 * @param fileName    文件名（已修复乱码）
 * @param format      格式（pdf/docx/image/...）
 * @param content     提取的纯文本（含版面结构标记和图片位置标记）
 * @param metadata    文档元数据
 * @param contentHash 内容哈希（normalize 后的 SHA-256）
 * @param status      解析状态
 * @param images      提取的图片列表（可为空）
 */
public record RawDocument(
        String id,
        String sourcePath,
        String fileName,
        String format,
        String content,
        DocumentMetadata metadata,
        String contentHash,
        ParseStatus status,
        List<DocumentImage> images
) {
    /** 兼容无图片的构造 */
    public RawDocument(String id, String sourcePath, String fileName, String format,
                       String content, DocumentMetadata metadata,
                       String contentHash, ParseStatus status) {
        this(id, sourcePath, fileName, format, content, metadata, contentHash, status, List.of());
    }
}
