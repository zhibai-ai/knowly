package com.knowly.core.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 文档元数据。
 *
 * @param fileSize    文件大小（字节）
 * @param pageCount   PDF 页数
 * @param author      作者
 * @param createdTime 文档创建时间
 * @param parserUsed  用了哪个解析器（如 TikaDocumentParser / PdfDocumentParser）
 * @param extra       格式特定元数据（如视频 ASR 时间戳，v0.2 预留）
 */
public record DocumentMetadata(
        long fileSize,
        int pageCount,
        String author,
        LocalDateTime createdTime,
        String parserUsed,
        Map<String, String> extra
) {}
