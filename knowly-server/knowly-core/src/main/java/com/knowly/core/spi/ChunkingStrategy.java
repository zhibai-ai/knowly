package com.knowly.core.spi;

import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import java.util.List;

/**
 * 分段策略 SPI。把文档切成 TextChunk 列表。
 * 实现类：StructureAwareChunking（两阶段，默认）、SemanticChunking（可选）、FixedSizeChunking（备用）。
 */
public interface ChunkingStrategy {
    /**
     * 把文档切成块。
     * @param doc 原始文档
     * @return 文本块列表
     */
    List<TextChunk> chunk(RawDocument doc);
}
