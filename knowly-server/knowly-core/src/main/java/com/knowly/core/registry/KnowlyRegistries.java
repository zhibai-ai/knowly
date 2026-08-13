package com.knowly.core.registry;

import com.knowly.core.spi.ChunkSinkFactory;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 注册表聚合——CLI/Web 启动时调用 {@link #bootstrap()} 一次性注册所有内置工厂。
 *
 * <p>设计原则：core 不依赖任何实现模块（ingest/clean/embed）。
 * 注册通过 SPI（{@link ServiceLoader}）发现：
 * <ul>
 *   <li>Sink Factory：各实现模块在 {@code META-INF/services/} 声明 ChunkSinkFactory 实现类</li>
 *   <li>Chunking/Dedup 策略同理</li>
 * </ul>
 *
 * <p>第三方扩展：实现 ChunkSinkFactory 接口 + SPI 声明，放入 classpath 即自动注册，
 * 无需改 core。
 */
public final class KnowlyRegistries {

    private static final Logger log = LoggerFactory.getLogger(KnowlyRegistries.class);

    public final SinkRegistry sinks = new SinkRegistry();
    public final ChunkingRegistry chunkings = new ChunkingRegistry();

    private KnowlyRegistries() {}

    /**
     * 创建并通过 SPI 引导注册所有可发现的工厂。
     */
    public static KnowlyRegistries bootstrap() {
        KnowlyRegistries r = new KnowlyRegistries();
        r.registerDefaults();
        return r;
    }

    /** 通过 SPI 注册内置组件（core 不依赖实现模块） */
    private void registerDefaults() {
        // ── Sink Factory：SPI 发现（实现模块在 META-INF/services/ 声明）──
        int sinkCount = 0;
        for (ChunkSinkFactory factory : ServiceLoader.load(ChunkSinkFactory.class)) {
            try {
                sinks.register(factory);
                sinkCount++;
            } catch (Exception e) {
                log.warn("注册 sink factory 失败: {}, cause={}", factory.getClass().getName(), e.getMessage());
            }
        }
        log.info("已通过 SPI 注册 {} 个 sink factory: {}", sinkCount, sinks.registeredTypes());

        // ── Chunking 策略：同样可走 SPI（v0.1 由 PipelineAssembler 注册默认实现）──
        // clean 模块的策略注册由 PipelineAssembler 启动时补充调用 registerChunking()
    }

    /** 补充注册一个 Chunking 策略（由 PipelineAssembler 启动时调用，注册 core 外的实现） */
    public void registerChunking(String name, ChunkingRegistry.Factory factory) {
        chunkings.register(name, factory);
    }

    /** 补充注册一个 Sink factory（非 SPI 场景手动注册用） */
    public void registerSink(ChunkSinkFactory factory) {
        sinks.register(factory);
    }
}
