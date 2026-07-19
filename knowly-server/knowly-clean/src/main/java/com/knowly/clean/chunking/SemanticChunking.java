package com.knowly.clean.chunking;

import com.knowly.common.enums.BlockType;
import com.knowly.common.util.HashUtils;
import com.knowly.core.model.ChunkMetadata;
import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.ChunkingStrategy;
import com.knowly.core.spi.EmbeddingProvider;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语义分段（SemanticChunking）—— v0.1 可选策略。
 *
 * <p>差异化武器：竞品 Unstructured OSS 完全没有 semantic chunking。
 *
 * <p>算法：
 * <ol>
 *   <li>先按句子/小段切分文本（粗切）</li>
 *   <li>对每个小段算 embedding</li>
 *   <li>计算相邻小段的 embedding 余弦相似度</li>
 *   <li>相似度低于阈值（默认 0.7）处切一刀——语义边界</li>
 *   <li>相邻语义块合并到接近 max_size</li>
 * </ol>
 *
 * <p>注意：此策略消耗 embedding API 调用，增加处理耗时和成本。
 * 默认不启用，用户显式配置 strategy: semantic 才开。
 */
public class SemanticChunking implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunking.class);

    /** 默认语义相似度阈值（低于此值处切一刀） */
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.7;

    /** 句子切分最小长度 */
    private static final int MIN_SENTENCE_LENGTH = 20;

    private final EmbeddingProvider embeddingProvider;
    private final double similarityThreshold;
    private final int maxSize;
    private final int overlap;

    /**
     * 创建语义分段器。
     *
     * @param embeddingProvider  embedding 提供者（必须已配置 API key）
     * @param maxSize            单个 chunk 最大字符数
     * @param overlap            相邻 chunk 重叠字符数
     * @param similarityThreshold 语义边界阈值（相邻小段相似度低于此值则切分）
     */
    public SemanticChunking(EmbeddingProvider embeddingProvider,
                            int maxSize, int overlap, double similarityThreshold) {
        this.embeddingProvider = embeddingProvider;
        this.maxSize = maxSize;
        this.overlap = overlap;
        this.similarityThreshold = similarityThreshold;
    }

    /** 简化构造（默认阈值 0.7） */
    public SemanticChunking(EmbeddingProvider embeddingProvider, int maxSize, int overlap) {
        this(embeddingProvider, maxSize, overlap, DEFAULT_SIMILARITY_THRESHOLD);
    }

    @Override
    public List<TextChunk> chunk(RawDocument doc) {
        log.debug("语义分段: doc={}, contentLength={}", doc.id(), doc.content().length());
        log.info("SemanticChunking 消耗 embedding API 调用");

        // 第一步：粗切为句子/小段
        List<String> sentences = splitIntoSentences(doc.content());
        log.debug("粗切为 {} 个小段", sentences.size());

        if (sentences.isEmpty()) return List.of();

        // 第二步：对每个小段算 embedding
        List<float[]> embeddings = embeddingProvider.embedBatch(sentences);

        // 第三步：计算相邻小段的余弦相似度，低于阈值处切分
        List<String> semanticBlocks = new ArrayList<>();
        StringBuilder currentBlock = new StringBuilder(sentences.get(0));

        for (int i = 1; i < sentences.size(); i++) {
            double similarity = cosineSimilarity(embeddings.get(i - 1), embeddings.get(i));

            if (similarity < similarityThreshold) {
                // 语义边界：flush 当前 block，开始新 block
                semanticBlocks.add(currentBlock.toString());
                currentBlock = new StringBuilder(sentences.get(i));
            } else {
                // 语义连续：拼接到当前 block
                currentBlock.append(sentences.get(i));
            }
        }
        // flush 最后的 block
        if (!currentBlock.isEmpty()) {
            semanticBlocks.add(currentBlock.toString());
        }
        log.debug("语义切分完成: {} 个语义块", semanticBlocks.size());

        // 第四步：按 max_size 进一步切分过大的语义块 + 组装 TextChunk
        List<TextChunk> result = new ArrayList<>();
        int ordinal = 0;
        for (String block : semanticBlocks) {
            if (block.length() > maxSize) {
                // 语义块太大，按 overlap 切分
                int start = 0;
                while (start < block.length()) {
                    int end = Math.min(start + maxSize, block.length());
                    result.add(createChunk(doc, ordinal, block.substring(start, end)));
                    ordinal++;
                    if (end >= block.length()) break;
                    start = Math.max(end - overlap, start + 1);
                }
            } else {
                result.add(createChunk(doc, ordinal, block));
                ordinal++;
            }
        }

        log.debug("语义分段完成: {} 个 chunk", result.size());
        return result;
    }

    /**
     * 按句子切分文本（句号/问号/感叹号/换行）。
     */
    private List<String> splitIntoSentences(String content) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            current.append(ch);

            // 句末标点 → 句子结束
            if ("。！？!?\n".indexOf(ch) >= 0) {
                if (current.length() >= MIN_SENTENCE_LENGTH) {
                    sentences.add(current.toString().strip());
                    current.setLength(0);
                }
            }
        }
        if (!current.isEmpty()) {
            sentences.add(current.toString().strip());
        }

        // 过滤空句子
        sentences.removeIf(String::isEmpty);
        return sentences;
    }

    /** 余弦相似度 */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 创建 TextChunk */
    private TextChunk createChunk(RawDocument doc, int ordinal, String text) {
        String chunkId = HashUtils.chunkId(doc.id(), ordinal);
        ChunkMetadata metadata = new ChunkMetadata(
                "", 0, 0, text.length(),
                BlockType.PARAGRAPH, List.of(), List.of()
        );
        return new TextChunk(chunkId, doc.id(), doc.sourcePath(), text, ordinal, metadata);
    }
}
