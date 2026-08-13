package com.knowly.core.pipeline;

import com.knowly.core.config.PipelineConfig;
import com.knowly.core.registry.KnowlyRegistries;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.ChunkingStrategy;
import com.knowly.core.spi.DedupStrategy;
import com.knowly.core.spi.DocumentParser;
import com.knowly.core.spi.EmbeddingProvider;
import com.knowly.core.spi.LayoutAnalyzer;
import com.knowly.core.spi.StateRepository;
import com.knowly.core.state.SqliteStateRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流水线组装器——把 {@link PipelineConfig} + 各实现组件装配成 {@link PipelineEngine}。
 *
 * <p>这是"配置驱动"的关键一环：CLI 和 Web 都通过本类把配置变成引擎实例，
 * 避免各自手写组件实例化逻辑（消除重复 + 保证两入口行为一致）。
 *
 * <p>分工：
 * <ul>
 *   <li>本类负责：从配置决定启用哪些组件、参数取值</li>
 *   <li>各实现（parser/cleaner/chunking/dedup/embedding）由调用方传入——
 *       因为这些实现分布在 core 外的模块，core 不能直接依赖</li>
 *   <li>Sink 由 {@link KnowlyRegistries} 按配置自动创建</li>
 * </ul>
 */
public final class PipelineAssembler {

    private static final Logger log = LoggerFactory.getLogger(PipelineAssembler.class);

    private final KnowlyRegistries registries;

    public PipelineAssembler(KnowlyRegistries registries) {
        this.registries = registries;
    }

    /**
     * 装配引擎。
     *
     * @param config             配置
     * @param parser             文档解析器（外部传入，因 core 不依赖 ingest 模块）
     * @param cleaner            文本清洗器（外部传入）
     * @param fileDedup          文件级去重（外部传入，可为 null）
     * @param chunkDedup         段落级去重（外部传入，可为 null）
     * @param embeddingProvider  向量化 provider（外部传入，可为 null——无向量库 sink 时）
     * @return 引擎实例（调用方负责 close）
     */
    public PipelineEngine assemble(
            PipelineConfig config,
            DocumentParser parser,
            com.knowly.core.spi.TextCleaner cleaner,
            DedupStrategy fileDedup,
            DedupStrategy chunkDedup,
            EmbeddingProvider embeddingProvider) {

        return assemble(config, parser, null, cleaner, fileDedup, chunkDedup, embeddingProvider);
    }

    /**
     * 装配引擎（含版面分析器）。
     */
    public PipelineEngine assemble(
            PipelineConfig config,
            DocumentParser parser,
            LayoutAnalyzer layoutAnalyzer,
            com.knowly.core.spi.TextCleaner cleaner,
            DedupStrategy fileDedup,
            DedupStrategy chunkDedup,
            EmbeddingProvider embeddingProvider) {

        // ── Sink：按配置自动创建 ──
        List<ChunkSink> sinks = createSinks(config);
        boolean needsEmbed = sinks.stream().anyMatch(ChunkSink::requiresEmbedding);

        // ── 分段策略：从注册表创建 ──
        ChunkingStrategy chunking = registries.chunkings.create(config.stages().clean().chunking());

        // ── 版面分析 ──
        boolean enableLayout = !"off".equalsIgnoreCase(config.stages().ingest().layoutAnalysis())
                && layoutAnalyzer != null;

        // ── 状态存储 ──
        StateRepository stateRepo = createStateRepository(config);

        // ── embedding：仅当有向量库 sink 且提供了 provider ──
        EmbeddingProvider actualEmbedding = needsEmbed ? embeddingProvider : null;
        if (needsEmbed && actualEmbedding == null) {
            log.warn("配置了向量库 sink 但未提供 EmbeddingProvider，向量库将无数据写入");
        }

        PipelineConfig.ConcurrencyConfig conc = config.concurrency();
        var engine = PipelineEngine.builder()
                .parser(parser)
                .layoutAnalyzer(layoutAnalyzer)
                .cleaner(cleaner)
                .fileDedup(fileDedup)
                .chunkDedup(chunkDedup)
                .chunking(chunking)
                .embeddingProvider(actualEmbedding)
                .stateRepository(stateRepo)
                .enableLayoutAnalysis(enableLayout)
                .concurrency(conc)   // 传并发配置（P1-7 用）
                .config(config);

        for (ChunkSink sink : sinks) {
            engine.addSink(sink);
        }

        return engine.build();
    }

    /** 根据配置的 sinks 段批量创建 sink 实例 */
    private List<ChunkSink> createSinks(PipelineConfig config) {
        java.nio.file.Path outputBase = config.output() != null ? java.nio.file.Path.of(config.output()) : null;

        if (config.sinks() == null || config.sinks().isEmpty()) {
            log.info("未配置 sinks，使用默认 Markdown + JSONL");
            // 默认 sink 的产出位置绑定到 outputDir（而非 JVM 工作目录）
            java.util.Map<String, Object> mdProps = new java.util.HashMap<>();
            java.util.Map<String, Object> jsonlProps = new java.util.HashMap<>();
            if (outputBase != null) {
                mdProps.put("dir", outputBase.resolve("00-clean").toString());
                jsonlProps.put("path", outputBase.resolve("01-chunks").resolve("chunks.jsonl").toString());
            }
            List<ChunkSink> defaults = new ArrayList<>();
            defaults.add(registries.sinks.create(new com.knowly.core.config.SinkConfig("markdown", mdProps)));
            defaults.add(registries.sinks.create(new com.knowly.core.config.SinkConfig("jsonl", jsonlProps)));
            return defaults;
        }

        // 用户配置的 sink：把 dir/path 的相对路径解析为相对于 outputDir（而非 JVM 工作目录）
        // 这样 --output ./out 时，配置里写 ./00-clean 会产到 ./out/00-clean，符合直觉
        List<com.knowly.core.config.SinkConfig> resolved = config.sinks().stream()
                .map(sc -> resolveSinkPaths(sc, outputBase))
                .toList();
        return registries.sinks.createAll(resolved);
    }

    /**
     * 把 sink 配置里的相对路径属性（dir/path）解析为相对于 outputDir 的绝对路径。
     * 已是绝对路径则保持不变。outputDir 为 null（未配置 output）时保持原样。
     */
    private com.knowly.core.config.SinkConfig resolveSinkPaths(
            com.knowly.core.config.SinkConfig sc, java.nio.file.Path outputBase) {
        if (outputBase == null) return sc;
        java.util.Map<String, Object> props = new java.util.HashMap<>(sc.properties());
        for (String key : new String[]{"dir", "path"}) {
            String val = sc.str(key);
            if (val != null) {
                java.nio.file.Path p = java.nio.file.Path.of(val);
                if (!p.isAbsolute()) {
                    props.put(key, outputBase.resolve(p).toString());
                }
            }
        }
        return new com.knowly.core.config.SinkConfig(sc.type(), props);
    }

    private StateRepository createStateRepository(PipelineConfig config) {
        if (config.state() == null || !"sqlite".equalsIgnoreCase(config.state().store())) {
            return null;
        }
        Path dbPath = config.state().path();
        // 相对路径绑定到 outputDir（与 sink 路径处理一致，保证产出/状态都在 --output 下）
        if (dbPath != null && config.output() != null && !dbPath.isAbsolute()) {
            dbPath = Path.of(config.output()).resolve(dbPath);
        }
        if (dbPath == null && config.output() != null) {
            dbPath = Path.of(config.output()).resolve(".knowly").resolve("knowly.db");
        }
        if (dbPath == null) {
            log.warn("无法确定 SQLite 状态库路径，断点续跑功能不可用");
            return null;
        }
        return new SqliteStateRepository(dbPath);
    }
}
