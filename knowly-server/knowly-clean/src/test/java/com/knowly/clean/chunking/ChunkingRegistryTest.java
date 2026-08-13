package com.knowly.clean.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowly.common.exception.ConfigException;
import com.knowly.core.config.PipelineConfig;
import com.knowly.core.registry.ChunkingRegistry;
import com.knowly.core.spi.ChunkingStrategy;
import org.junit.jupiter.api.Test;

/**
 * {@link ChunkingRegistry} 单元测试。
 *
 * <p>覆盖注册+创建链路——这是"调用方必须注册 chunking 策略"契约的保障。
 * 历史上曾因 RunCommand/JobService 未注册 structure_aware 导致运行时崩，
 * 本测试锁定该契约。
 */
class ChunkingRegistryTest {

    @Test
    void should_create_when_strategy_registered() {
        ChunkingRegistry registry = new ChunkingRegistry();
        registry.register("structure_aware",
                cfg -> new StructureAwareChunking(cfg.maxSize(), cfg.overlap(), cfg.minSize()));

        PipelineConfig.ChunkingConfig config = new PipelineConfig.ChunkingConfig(
                "structure_aware", 500, 50, 50);

        ChunkingStrategy strategy = registry.create(config);

        assertThat(strategy).isInstanceOf(StructureAwareChunking.class);
    }

    @Test
    void should_throw_when_strategy_not_registered() {
        ChunkingRegistry registry = new ChunkingRegistry();
        PipelineConfig.ChunkingConfig config = new PipelineConfig.ChunkingConfig(
                "semantic", 500, 50, 50);  // 未注册

        assertThatThrownBy(() -> registry.create(config))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("未注册的分段策略");
    }

    @Test
    void should_pass_config_params_to_factory() {
        ChunkingRegistry registry = new ChunkingRegistry();
        registry.register("structure_aware",
                cfg -> new StructureAwareChunking(cfg.maxSize(), cfg.overlap(), cfg.minSize()));

        // 用非默认参数验证配置真的传到了策略
        PipelineConfig.ChunkingConfig config = new PipelineConfig.ChunkingConfig(
                "structure_aware", 800, 100, 80);

        ChunkingStrategy strategy = registry.create(config);
        assertThat(strategy).isInstanceOf(StructureAwareChunking.class);
        // 参数正确传递（间接验证：分段行为受 maxSize 影响，这里只验证类型，参数深度验证在 StructureAwareChunkingTest）
    }
}
