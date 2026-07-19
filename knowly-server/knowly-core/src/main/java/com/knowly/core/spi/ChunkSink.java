package com.knowly.core.spi;

import com.knowly.core.model.EmbeddedChunk;
import com.knowly.core.model.TextChunk;
import java.util.List;

/**
 * 产出目标 SPI——知了 v1.1 最核心的新增抽象。
 *
 * <p>所有产出目标（Markdown/JSONL/Qdrant/PgVector）实现同一接口。
 * 向量库只是其中一种 sink。不配任何 sink 时默认启用 Markdown + JSONL。
 *
 * <p>幂等性：所有 sink 的写入按 chunk/document id 主键幂等（覆盖写），
 * 中断重跑不产生重复。
 */
public interface ChunkSink extends AutoCloseable {

    /**
     * sink 类型标识（YAML 里 type 字段匹配用）。
     * @return 如 "markdown" / "jsonl" / "qdrant" / "pgvector"
     */
    String type();

    /**
     * 是否需要 embedding。
     * <p>向量库返回 true（消费 EmbeddedChunk），Markdown/JSONL 返回 false（消费 TextChunk）。
     * PipelineEngine 根据此判断是否启用 embed 阶段——全 false 则跳过 embed，省 API 开销。
     *
     * @return true 如果此 sink 需要向量
     */
    boolean requiresEmbedding();

    /**
     * 写入清洗阶段产物（所有 sink 都实现）。
     *
     * @param chunks 文本块列表
     */
    void write(List<TextChunk> chunks);

    /**
     * 写入向量化后产物（仅 requiresEmbedding()==true 的 sink 实现）。
     *
     * @param chunks 向量化后的块列表
     */
    default void writeEmbedded(List<EmbeddedChunk> chunks) {
        // 非 embedding sink 空实现
    }

    /**
     * 删除某文档的所有产物。
     * <p>用于增量更新清理旧数据 + 跨 sink 补偿事务。
     *
     * @param documentId 文档 ID
     */
    void deleteByDocument(String documentId);

    @Override
    void close();
}
