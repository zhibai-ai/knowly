package com.knowly.embed.sink.pgvector;

import com.knowly.common.exception.ConfigException;
import com.knowly.common.exception.ErrorCode;
import com.knowly.core.config.SinkConfig;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.ChunkSinkFactory;

/**
 * {@link PgVectorSink} 的工厂。配置项（密码等敏感值用 ${VAR} 占位，由 ConfigLoader 解析，绝不硬编码）：
 * <ul>
 *   <li>host: PostgreSQL 主机（默认 localhost）</li>
 *   <li>port: 端口（默认 5433）</li>
 *   <li>database: 数据库名（默认 knowly）</li>
 *   <li>user: 用户名（默认 knowly）</li>
 *   <li>password: 密码（必填，通过 ${PG_PASSWORD} 注入）</li>
 *   <li>table: 表名（默认 knowledge_chunks）</li>
 *   <li>dimension: 向量维度（默认 1024）</li>
 * </ul>
 *
 * <p>安全铁律：password 永不硬编码进源码。缺失时明确报错而非用默认值。
 */
public class PgVectorSinkFactory implements ChunkSinkFactory {

    @Override
    public String type() {
        return "pgvector";
    }

    @Override
    public ChunkSink create(SinkConfig config) {
        String password = config.str("password");
        if (password == null || password.isBlank() || password.contains("${")) {
            // 密钥缺失：绝不静默用默认密码。明确报错，指引设置环境变量。
            throw new ConfigException(ErrorCode.CONFIG_002,
                    "pgvector sink 未配置 password",
                    "请设置环境变量 PG_PASSWORD，或在配置 sinks 段的 pgvector.password 填写。密钥绝不硬编码进源码。");
        }
        return new PgVectorSink(
                config.str("host", "localhost"),
                config.intVal("port", 5433),
                config.str("database", "knowly"),
                config.str("user", "knowly"),
                password,
                config.str("table", "knowledge_chunks"),
                config.intVal("dimension", 1024));
    }
}
