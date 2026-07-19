package com.knowly.core.spi;

import com.knowly.core.model.EmbeddedChunk;
import java.util.List;

/**
 * Embedding 模型提供者 SPI。
 * 默认实现 DashScopeEmbeddingProvider（text-embedding-v3 / 1024 维）。
 */
public interface EmbeddingProvider {

    /**
     * 单条文本 → 向量。
     *
     * @param text 文本
     * @return 向量
     */
    float[] embed(String text);

    /**
     * 批量文本 → 向量列表（推荐，省 API 调用）。
     *
     * @param texts 文本列表
     * @return 向量列表（与输入一一对应）
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * 向量维度（MVP 固定 1024，text-embedding-v3）。
     * 换 embedding 模型必须同步改维度并重建索引。
     *
     * @return 维度数
     */
    int dimension();
}
