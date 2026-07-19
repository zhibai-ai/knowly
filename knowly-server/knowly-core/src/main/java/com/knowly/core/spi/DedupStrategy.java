package com.knowly.core.spi;

import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import java.util.List;

/**
 * 去重策略 SPI。支持文件级（精确哈希）和段落级（近似 MinHash）。
 */
public interface DedupStrategy {

    /** 文件级去重检查 */
    DedupResult check(RawDocument doc);

    /** 段落级去重检查（对一批 chunk） */
    List<DedupResult> checkChunks(List<TextChunk> chunks);

    /** 去重结果 */
    record DedupResult(boolean isDuplicate, String duplicateOf, String reason) {
        public static DedupResult unique() { return new DedupResult(false, null, null); }
        public static DedupResult duplicateOf(String id) { return new DedupResult(true, id, null); }
    }
}
