package com.knowly.api.service;

import com.knowly.clean.cleaner.DefaultTextCleaner;
import com.knowly.clean.chunking.StructureAwareChunking;
import com.knowly.clean.dedup.HashDedupStrategy;
import com.knowly.clean.dedup.MinHashDedupStrategy;
import com.knowly.common.util.HashUtils;
import com.knowly.core.config.PipelineConfig;
import com.knowly.core.config.ConfigLoader;
import com.knowly.core.event.PipelineEvent;
import com.knowly.core.event.PipelineEventListener;
import com.knowly.core.pipeline.PipelineAssembler;
import com.knowly.core.pipeline.PipelineEngine;
import com.knowly.core.registry.KnowlyRegistries;
import com.knowly.embed.dashscope.DashScopeEmbeddingProvider;
import com.knowly.ingest.CompositeParser;
import com.knowly.ingest.ocr.TesseractOcrEngine;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 清洗任务服务。负责组装 PipelineEngine 并异步执行。
 *
 * <p><b>配置驱动</b>：sink/分段/OCR 等参数全部走 {@link PipelineConfig}，
 * 禁止 switch-case 硬编码（修复历史问题）。
 *
 * <p><b>互斥</b>：同一时间只允许一个清洗任务。用 {@link AtomicBoolean#compareAndSet}
 * 做无竞态的占用（修复 volatile check-then-action 问题）。
 *
 * <p><b>优雅停机</b>：持有当前 engine 引用，cancel 直接调 engine.cancel()。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /** 互斥占用标志（compareAndSet 保证无竞态） */
    private final AtomicBoolean occupied = new AtomicBoolean(false);

    private volatile String currentJobId = null;
    private volatile PipelineEngine currentEngine = null;   // 持有引用以支持 cancel
    private volatile PipelineEngine.PipelineStats lastStats = null;

    private final List<PipelineEventListener> sseListeners = new CopyOnWriteArrayList<>();

    /**
     * 异步启动清洗任务。
     *
     * @param jobId        任务 ID（派生，保证断点续跑）
     * @param inputPath    输入路径
     * @param outputPath   输出路径
     * @param sinkTypes    sink 类型列表（前端选择；若为空用配置默认）
     */
    @Async
    public void startJob(String jobId, List<String> inputPaths, String outputPath, List<String> sinkTypes) {
        // 互斥占用
        if (!occupied.compareAndSet(false, true)) {
            log.warn("互斥拦截：已有任务在运行，jobId={} 被拒绝", jobId);
            return;
        }

        try {
            log.info("清洗任务启动: jobId={}, inputs={}, output={}, sinks={}",
                    jobId, inputPaths, outputPath, sinkTypes);

            // ── 加载配置（三层合并，命令行覆盖 input/output）──
            java.util.Map<String, String> cliOverrides = new java.util.HashMap<>();
            // 配置层只放首个路径（ConfigLoader 会校验路径存在性）；真实多路径输入由 engine 显式传参
            cliOverrides.put("pipeline.input", inputPaths.get(0));
            cliOverrides.put("pipeline.output", outputPath);
            // 若前端传了 sink 选择，覆盖配置的 sinks 段
            PipelineConfig baseConfig = ConfigLoader.load(null, cliOverrides);
            PipelineConfig config = applySinkOverride(baseConfig, sinkTypes);

            // ── 注册表（SPI 自动发现 sink factory）──
            KnowlyRegistries registries = KnowlyRegistries.bootstrap();

            // ── 注册分段策略（core 不依赖 clean，由调用方注册实现）──
            registries.registerChunking("structure_aware",
                    cfg -> new StructureAwareChunking(cfg.maxSize(), cfg.overlap(), cfg.minSize()));

            // ── 实现组件（core 不依赖实现模块）──
            PipelineConfig.IngestStage ingest = config.stages().ingest();
            var ocrEngine = new TesseractOcrEngine(ingest.ocr().languages());
            var parser = new CompositeParser(ocrEngine, ingest.ocr().languages());
            // 版面分析器：配置 layout_analysis != off 时启用
            com.knowly.core.spi.LayoutAnalyzer layoutAnalyzer = null;
            if (!"off".equalsIgnoreCase(ingest.layoutAnalysis())) {
                layoutAnalyzer = new com.knowly.ingest.layout.RuleBasedLayoutAnalyzer();
            }
            var cleaner = new DefaultTextCleaner();
            var fileDedup = config.stages().clean().dedup().fileLevel().enabled()
                    ? new HashDedupStrategy() : null;
            var chunkDedup = config.stages().clean().dedup().paragraphLevel().enabled()
                    ? new MinHashDedupStrategy(config.stages().clean().dedup().paragraphLevel().threshold())
                    : null;

            // ── embedding（仅当配置了向量库 sink 时）──
            boolean needsEmbed = config.sinks().stream().anyMatch(
                    s -> "pgvector".equals(s.type()) || "qdrant".equals(s.type()));
            DashScopeEmbeddingProvider embeddingProvider = null;
            if (needsEmbed && config.stages().embed() != null) {
                String apiKey = config.stages().embed().apiKey();
                if (apiKey == null || apiKey.isBlank() || apiKey.contains("${")) {
                    apiKey = getApiKeyFromConfig();
                }
                if (apiKey != null && !apiKey.isBlank()) {
                    embeddingProvider = new DashScopeEmbeddingProvider(
                            apiKey, config.stages().embed().qpsLimit(), config.errorHandling().retry().max());
                } else {
                    log.warn("需要 embedding 但未配置 DashScope API Key，向量库 sink 将无数据写入");
                }
            }

            // ── 装配并执行 ──
            var assembler = new PipelineAssembler(registries);
            var engine = assembler.assemble(config, parser, layoutAnalyzer, cleaner,
                    fileDedup, chunkDedup, embeddingProvider);
            currentEngine = engine;

            engine.addListener(event -> {
                for (PipelineEventListener listener : sseListeners) {
                    try { listener.onEvent(event); } catch (Exception ignored) {}
                }
            });

            currentJobId = jobId;
            List<Path> inputDirList = inputPaths.stream().map(Path::of).toList();
            var stats = engine.execute(jobId, inputDirList, Path.of(outputPath));
            lastStats = stats;

        } catch (Exception e) {
            log.error("清洗任务异常: jobId={}, cause={}", jobId, e.getMessage(), e);
        } finally {
            currentEngine = null;
            occupied.set(false);
        }
    }

    /**
     * 取消当前运行中的任务（触发优雅停机）。
     */
    public boolean cancelCurrent() {
        PipelineEngine engine = currentEngine;
        if (engine == null) return false;
        engine.cancel();
        return true;
    }

    /**
     * 检测是否有未完成任务（跨进程：基于内存标志；同进程内的并发由 occupied 保证）。
     * v0.1 单进程，此实现足够。多进程互斥需落 DB（clean_jobs 表唯一约束，v0.2 完善）。
     */
    public boolean hasUnfinished() {
        return occupied.get();
    }

    /** 是否正在运行（互斥标志） */
    public boolean isRunning() {
        return occupied.get();
    }

    public String getCurrentJobId() { return currentJobId; }
    public PipelineEngine.PipelineStats getLastStats() { return lastStats; }

    /**
     * 创建 jobId（派生，保证断点续跑能匹配）。
     */
    public String createJobId(String inputPath, String configFingerprint) {
        return HashUtils.jobId(inputPath, configFingerprint);
    }

    /** 注册 SSE 事件监听器 */
    public void addSseListener(PipelineEventListener listener) { sseListeners.add(listener); }
    public void removeSseListener(PipelineEventListener listener) { sseListeners.remove(listener); }

    // ────────────────────────────────────────────────────────────
    // 内部
    // ────────────────────────────────────────────────────────────

    /**
     * 前端选了 sink 时，覆盖配置的 sinks 段。
     *
     * <p>为每种 sink 提供含环境变量占位的默认参数（密码等敏感值走 ${VAR} 注入，
     * 由各 SinkFactory 在创建时解析，绝不硬编码进源码）。markdown/jsonl 用空属性
     * （Factory 有合理默认）；向量库提供连接参数占位。
     */
    private PipelineConfig applySinkOverride(PipelineConfig base, List<String> sinkTypes) {
        if (sinkTypes == null || sinkTypes.isEmpty()) return base;
        List<com.knowly.core.config.SinkConfig> sinks = sinkTypes.stream()
                .map(this::defaultSinkConfig)
                .toList();
        return new PipelineConfig(base.name(), base.input(), base.output(), base.state(),
                base.stages(), sinks, base.errorHandling(), base.concurrency());
    }

    /** 单个 sink 类型的默认参数（敏感值直接从环境变量读，绝不硬编码进源码） */
    private com.knowly.core.config.SinkConfig defaultSinkConfig(String type) {
        // markdown/jsonl 的 dir/path 写相对路径（相对 output），由 PipelineAssembler 绑定到 outputDir
        return switch (type) {
            case "markdown" -> new com.knowly.core.config.SinkConfig("markdown",
                    java.util.Map.of("dir", "00-clean", "include_chunk_markers", true));
            case "jsonl" -> new com.knowly.core.config.SinkConfig("jsonl",
                    java.util.Map.of("path", "01-chunks/chunks.jsonl"));
            case "qdrant" -> new com.knowly.core.config.SinkConfig("qdrant", java.util.Map.of(
                    "host", envOrDefault("QDRANT_HOST", "localhost"),
                    "port", Integer.parseInt(envOrDefault("QDRANT_PORT", "6333")),
                    "collection", envOrDefault("QDRANT_COLLECTION", "knowly_chunks"),
                    "dimension", 1024,
                    "batch_size", 100));
            case "pgvector" -> new com.knowly.core.config.SinkConfig("pgvector", java.util.Map.of(
                    "host", envOrDefault("PG_HOST", "localhost"),
                    "port", Integer.parseInt(envOrDefault("PG_PORT", "5433")),
                    "database", envOrDefault("PG_DATABASE", "knowly"),
                    "user", envOrDefault("PG_USER", "knowly"),
                    "password", System.getenv("PG_PASSWORD"),   // 密码只从环境变量读
                    "table", envOrDefault("PG_TABLE", "knowledge_chunks"),
                    "dimension", 1024));
            default -> new com.knowly.core.config.SinkConfig(type, java.util.Map.of());
        };
    }

    /** 读环境变量，缺失时用默认值 */
    private static String envOrDefault(String env, String def) {
        String v = System.getenv(env);
        return (v == null || v.isBlank()) ? def : v;
    }

    /** 从 app_config 表读 API Key（Web 设置页保存的值） */
    private String getApiKeyFromConfig() {
        String knowlyHome = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly");
        String dbPath = Path.of(knowlyHome, "knowly.db").toString();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM app_config WHERE key = ?");
            ps.setString(1, "dashscope_api_key");
            java.sql.ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (Exception ignored) {}
        return null;
    }
}
