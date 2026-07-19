package com.knowly.core.spi;

import com.knowly.core.model.RawDocument;

/**
 * 文本清洗器 SPI。清洗单个文档的文本（编码修复、去噪、繁简统一）。
 * 多个 cleaner 可串联（责任链模式）。
 */
public interface TextCleaner {
    /**
     * 清洗文本。
     * @param rawText 原始文本
     * @param source  来源文档
     * @return 清洗后的文本
     */
    String clean(String rawText, RawDocument source);
}
