package com.knowly.core.spi;

import com.knowly.core.model.EmbeddedChunk;
import java.util.List;

/**
 * 向量检索接口——仅供知了自测/校验使用。
 *
 * <p>知了不做查询服务（纯造库）。此接口仅供自测/校验，标注 @VisibleForTesting，
 * 不进生产路径。生产 RAG 查询应由消费方直连向量库。
 *
 * <p>实现者：PgVectorSink / QdrantSink 同时实现 ChunkSink 和 VectorSearchable。
 */
public interface VectorSearchable {

    /**
     * 相似度检索——仅供自测/校验。
     *
     * @param queryVector 查询向量
     * @param topK        返回前 K 个
     * @return 相似度最高的 K 个 EmbeddedChunk
     */
    List<EmbeddedChunk> search(float[] queryVector, int topK);
}
