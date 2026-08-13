package com.knowly.core.registry;

import com.knowly.common.exception.ConfigException;
import com.knowly.common.exception.ErrorCode;
import com.knowly.core.config.SinkConfig;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.ChunkSinkFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sink 注册表——工厂+注册表模式，避免硬编码 switch-case。
 *
 * <p>注册各 {@link ChunkSinkFactory}，按 {@link SinkConfig#type()} 路由创建。
 * 新增 sink 类型只需：①写 Factory ②注册，不动核心代码。
 *
 * <p>线程安全（{@link ConcurrentHashMap}）。
 */
public final class SinkRegistry {

    private static final Logger log = LoggerFactory.getLogger(SinkRegistry.class);

    private final Map<String, ChunkSinkFactory> factories = new ConcurrentHashMap<>();

    /** 注册一个 sink 工厂 */
    public void register(ChunkSinkFactory factory) {
        Objects.requireNonNull(factory, "factory 不能为空");
        String type = factory.type();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("factory.type() 不能为空");
        }
        factories.put(type, factory);
        log.debug("已注册 sink factory: {}", type);
    }

    /** 按配置创建 sink 实例 */
    public ChunkSink create(SinkConfig config) {
        Objects.requireNonNull(config, "config 不能为空");
        ChunkSinkFactory factory = factories.get(config.type());
        if (factory == null) {
            throw new ConfigException(ErrorCode.CONFIG_005,
                    "未注册的 sink 类型: " + config.type(),
                    "已注册类型: " + factories.keySet() + "。请在配置中改用已注册类型，或注册对应 Factory。");
        }
        return factory.create(config);
    }

    /** 批量创建（一次清洗的所有 sink） */
    public List<ChunkSink> createAll(List<SinkConfig> configs) {
        return configs.stream().map(this::create).toList();
    }

    /** 已注册的所有类型（诊断/错误信息用） */
    public List<String> registeredTypes() {
        return List.copyOf(factories.keySet());
    }
}
