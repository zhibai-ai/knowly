package com.knowly.embed.sink.qdrant;

import com.knowly.core.config.SinkConfig;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.ChunkSinkFactory;

/**
 * {@link QdrantSink} 的工厂。配置项（密码等敏感值用 ${VAR} 占位，由 ConfigLoader 解析）：
 * <ul>
 *   <li>host: Qdrant 主机（默认 localhost）</li>
 *   <li>port: HTTP 端口（默认 6333）</li>
 *   <li>collection: collection 名称（默认 knowly_chunks）</li>
 *   <li>dimension: 向量维度（默认 1024）</li>
 *   <li>batch_size: 批量写入大小（默认 100）</li>
 * </ul>
 */
public class QdrantSinkFactory implements ChunkSinkFactory {

    @Override
    public String type() {
        return "qdrant";
    }

    @Override
    public ChunkSink create(SinkConfig config) {
        return new QdrantSink(
                config.str("host", "localhost"),
                config.intVal("port", 6333),
                config.str("collection", "knowly_chunks"),
                config.intVal("dimension", 1024),
                config.intVal("batch_size", 100));
    }
}
