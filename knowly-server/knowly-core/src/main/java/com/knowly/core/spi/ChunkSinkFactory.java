package com.knowly.core.spi;

import com.knowly.core.config.SinkConfig;

/**
 * Sink 工厂——每种 sink 类型对应一个实现。配合 {@link SinkRegistry} 实现"加新 sink 不动核心"。
 *
 * <p>实现类放各 sink 的包内（如 {@code com.knowly.embed.sink.pgvector.PgVectorSinkFactory}），
 * 由运行入口（CLI/Web）注册到 SinkRegistry。
 */
public interface ChunkSinkFactory {

    /** 本工厂能创建的 sink 类型标识（与 YAML 里 {@code type: xxx} 匹配） */
    String type();

    /**
     * 根据配置创建 sink 实例。
     *
     * @param config 此 sink 的配置（type 已剥离，properties 内含该 sink 的全部参数）
     * @return sink 实例
     */
    ChunkSink create(SinkConfig config);
}
