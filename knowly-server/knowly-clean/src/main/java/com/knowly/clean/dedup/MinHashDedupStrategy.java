package com.knowly.clean.dedup;

import com.clearspring.analytics.stream.membership.BloomFilter;
import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.DedupStrategy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 段落级近似去重策略。
 *
 * <p>算法（简化版 MinHash + 精确 Jaccard）：
 * <ol>
 *   <li>对每个 TextChunk 提取 N-gram（默认 3-gram 字符）作为特征集</li>
 *   <li>计算两个 chunk 的 Jaccard 相似度</li>
 *   <li>相似度 > 阈值（默认 0.85）→ 近似重复，标记但不删除</li>
 * </ol>
 *
 * <p>[待完善] v0.1 用精确 Jaccard（O(n²) 比对）。规模大时换 MinHash+LSH（stream-lib）分桶优化。
 * 对知了单次清洗的 chunk 量级（几千个），精确比对可接受。
 *
 * <p>注意：只标记不删除（保留对照版本），由使用方/人工决定是否合并。
 */
public class MinHashDedupStrategy implements DedupStrategy {

    private static final Logger log = LoggerFactory.getLogger(MinHashDedupStrategy.class);

    /** N-gram 的 N（字符级） */
    private static final int N_GRAM = 3;
    /** 默认相似度阈值 */
    private static final double DEFAULT_THRESHOLD = 0.85;

    private final double threshold;

    /**
     * 已处理过的 chunk 特征（用于比对）。
     * 线程安全：本策略实例被流水线共享，多文件并发调 checkChunks——
     * 一个线程 add 时另一个线程在遍历，普通 ArrayList 会抛 ConcurrentModificationException
     * （曾致并发清洗时随机文件失败 cause=null）。CopyOnWriteArrayList 遍历拿快照，写复制开销
     * 在单次清洗几千 chunk 的量级下可忽略。
     */
    private final List<ChunkFeatures> processed = new CopyOnWriteArrayList<>();

    public MinHashDedupStrategy() {
        this(DEFAULT_THRESHOLD);
    }

    public MinHashDedupStrategy(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public DedupResult check(RawDocument doc) {
        // 段落级策略不做文件级去重
        return DedupResult.unique();
    }

    @Override
    public List<DedupResult> checkChunks(List<TextChunk> chunks) {
        List<DedupResult> results = new ArrayList<>(chunks.size());

        for (TextChunk chunk : chunks) {
            Set<String> ngrams = extractNgrams(chunk.text());
            ChunkFeatures features = new ChunkFeatures(chunk.id(), ngrams);

            String duplicateOf = findNearDuplicate(features);
            if (duplicateOf != null) {
                log.debug("段落级近似去重命中: {} 近似于 {}", chunk.id(), duplicateOf);
                results.add(new DedupResult(true, duplicateOf,
                        "Jaccard similarity > " + threshold));
            } else {
                processed.add(features);
                results.add(DedupResult.unique());
            }
        }
        return results;
    }

    /** 在已处理的 chunk 中找近似重复 */
    private String findNearDuplicate(ChunkFeatures features) {
        for (ChunkFeatures existing : processed) {
            double similarity = jaccardSimilarity(features.ngrams, existing.ngrams);
            if (similarity >= threshold) {
                return existing.chunkId;
            }
        }
        return null;
    }

    /** 计算 Jaccard 相似度：|交集| / |并集| */
    private static double jaccardSimilarity(Set<String> setA, Set<String> setB) {
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;

        int intersection = 0;
        // 遍历较小的集合
        Set<String> smaller = setA.size() <= setB.size() ? setA : setB;
        Set<String> larger = setA.size() <= setB.size() ? setB : setA;
        for (String s : smaller) {
            if (larger.contains(s)) intersection++;
        }

        int union = setA.size() + setB.size() - intersection;
        return union == 0 ? 0 : (double) intersection / union;
    }

    /** 提取字符级 N-gram 特征集 */
    private static Set<String> extractNgrams(String text) {
        if (text == null || text.length() < N_GRAM) {
            return Set.of(text == null ? "" : text);
        }
        Set<String> ngrams = new HashSet<>();
        for (int i = 0; i <= text.length() - N_GRAM; i++) {
            ngrams.add(text.substring(i, i + N_GRAM));
        }
        return ngrams;
    }

    /** chunk 的特征 */
    private record ChunkFeatures(String chunkId, Set<String> ngrams) {}
}
