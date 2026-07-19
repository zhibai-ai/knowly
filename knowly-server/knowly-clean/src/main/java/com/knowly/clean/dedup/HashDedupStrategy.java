package com.knowly.clean.dedup;

import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.DedupStrategy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件级精确去重策略。
 *
 * <p>算法：对每个 RawDocument 的 contentHash 做精确比对。哈希相同 → 完全重复，
 * 保留首个，其余标记 DUPLICATE_OF:xxx。
 *
 * <p>注意：此策略维护一个内存中的哈希集合。生命周期与单次清洗任务绑定
 *（每次清洗任务新建一个实例）。
 */
public class HashDedupStrategy implements DedupStrategy {

    private static final Logger log = LoggerFactory.getLogger(HashDedupStrategy.class);

    /** contentHash → documentId（已见过的文档） */
    private final Map<String, String> seenHashes = new ConcurrentHashMap<>();

    @Override
    public DedupResult check(RawDocument doc) {
        String hash = doc.contentHash();
        String existing = seenHashes.putIfAbsent(hash, doc.id());
        if (existing != null) {
            // 哈希已存在，是重复文档
            log.debug("文件级去重命中: {} 重复于 {}", doc.fileName(), existing);
            return DedupResult.duplicateOf(existing);
        }
        return DedupResult.unique();
    }

    @Override
    public List<DedupResult> checkChunks(List<TextChunk> chunks) {
        // 文件级策略不做段落级去重，全部标记为 unique
        List<DedupResult> results = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            results.add(DedupResult.unique());
        }
        return results;
    }
}
