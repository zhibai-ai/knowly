package com.knowly.core.registry;

import com.knowly.common.exception.ConfigException;
import com.knowly.common.exception.ErrorCode;
import com.knowly.core.config.PipelineConfig;
import com.knowly.core.spi.ChunkingStrategy;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分段策略注册表。按 {@code strategy} 名称路由到具体实现。
 *
 * @param <T> 实现需是 ChunkingStrategy
 */
public final class ChunkingRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChunkingRegistry.class);

    /** 工厂接口：根据分段配置创建策略实例 */
    @FunctionalInterface
    public interface Factory {
        ChunkingStrategy create(PipelineConfig.ChunkingConfig config);
    }

    private final Map<String, Factory> factories = new ConcurrentHashMap<>();

    public void register(String strategy, Factory factory) {
        Objects.requireNonNull(strategy, "strategy 不能为空");
        Objects.requireNonNull(factory, "factory 不能为空");
        factories.put(strategy, factory);
        log.debug("已注册 chunking factory: {}", strategy);
    }

    public ChunkingStrategy create(PipelineConfig.ChunkingConfig config) {
        Objects.requireNonNull(config, "config 不能为空");
        Factory factory = factories.get(config.strategy());
        if (factory == null) {
            throw new ConfigException(ErrorCode.CONFIG_005,
                    "未注册的分段策略: " + config.strategy(),
                    "已注册策略: " + factories.keySet());
        }
        return factory.create(config);
    }
}
