package com.knowly.cli.command;

import com.knowly.clean.chunking.StructureAwareChunking;
import com.knowly.clean.cleaner.DefaultTextCleaner;
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
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * run 命令——组装并执行清洗流水线。
 *
 * <p><b>配置驱动</b>：所有参数（分段策略、sink 选择、OCR 语言、去重阈值、并发度等）
 * 全部从 pipeline.yaml 读取，禁止硬编码。
 * 命令行 {@code --input}/{@code --output} 覆盖 YAML 的对应字段。
 */
public class RunCommand {

    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    public void execute(String[] args) throws Exception {
        // 解析命令行参数
        String configPath = null;
        String templateName = null;
        Map<String, String> cliOverrides = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = args[++i];
                case "--template" -> templateName = args[++i];
                case "--input" -> cliOverrides.put("pipeline.input", args[++i]);
                case "--output" -> cliOverrides.put("pipeline.output", args[++i]);
            }
        }

        if (configPath == null && templateName == null) {
            System.err.println("错误：必须指定 --config <文件> 或 --template <名称>");
            System.exit(1);
        }

        // ── 加载配置（三层合并：默认 → 用户配置/template → 命令行覆盖）──
        Path resolvedConfigPath = resolveConfigPath(configPath, templateName);
        PipelineConfig config = ConfigLoader.load(resolvedConfigPath, cliOverrides);

        if (config.input() == null || config.output() == null) {
            System.err.println("错误：必须指定 --input 和 --output（或在配置中定义）");
            System.exit(1);
        }

        Path inputPath = Path.of(config.input());
        Path outputPath = Path.of(config.output());
        log.info("知了清洗启动: input={}, output={}", inputPath, outputPath);

        // ── 注册表（SPI 自动发现所有 sink factory）──
        KnowlyRegistries registries = KnowlyRegistries.bootstrap();

        // ── 注册分段策略（core 不依赖 clean，由调用方注册实现）──
        // structure_aware 是默认策略；其他（semantic/fixed）可按需补充
        registries.registerChunking("structure_aware",
                cfg -> new StructureAwareChunking(cfg.maxSize(), cfg.overlap(), cfg.minSize()));

        // ── 外部传入的实现组件（core 不依赖实现模块）──
        PipelineConfig.IngestStage ingest = config.stages().ingest();
        var ocrEngine = new TesseractOcrEngine(ingest.ocr().languages());
        var parser = new CompositeParser(ocrEngine, ingest.ocr().languages());
        // 版面分析器：配置 layout_analysis != off 时启用（双栏检测/页眉页脚/标题层级）
        com.knowly.core.spi.LayoutAnalyzer layoutAnalyzer = null;
        if (!"off".equalsIgnoreCase(ingest.layoutAnalysis())) {
            layoutAnalyzer = new com.knowly.ingest.layout.RuleBasedLayoutAnalyzer();
            log.info("版面分析已启用: mode={}", ingest.layoutAnalysis());
        }
        var cleaner = new DefaultTextCleaner();
        var fileDedup = config.stages().clean().dedup().fileLevel().enabled()
                ? new HashDedupStrategy() : null;
        var chunkDedup = config.stages().clean().dedup().paragraphLevel().enabled()
                ? new MinHashDedupStrategy(config.stages().clean().dedup().paragraphLevel().threshold())
                : null;

        // ── embedding provider（仅当配置了向量库 sink 时）──
        boolean needsEmbed = config.sinks().stream().anyMatch(
                s -> "pgvector".equals(s.type()) || "qdrant".equals(s.type()));
        DashScopeEmbeddingProvider embeddingProvider = null;
        if (needsEmbed && config.stages().embed() != null) {
            String apiKey = config.stages().embed().apiKey();
            if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("${")) {
                embeddingProvider = new DashScopeEmbeddingProvider(
                        apiKey, config.stages().embed().qpsLimit(), config.errorHandling().retry().max());
            }
        }

        // ── 装配引擎 ──
        var assembler = new PipelineAssembler(registries);
        var engine = assembler.assemble(config, parser, layoutAnalyzer, cleaner,
                fileDedup, chunkDedup, embeddingProvider);

        engine.addListener(this::printProgress);

        // ── jobId 派生（同输入同配置复用，保证断点续跑）──
        // 指纹纳入影响产出的关键参数：改任一项都应视为新任务（不复用旧断点）
        var ck = config.stages().clean().chunking();
        String configFingerprint = String.join("|",
                config.name(),
                "chunk=" + ck.strategy() + ":" + ck.maxSize() + ":" + ck.overlap(),
                "sinks=" + config.sinks().stream().map(com.knowly.core.config.SinkConfig::type)
                        .reduce("", (a, b) -> a + "," + b),
                "ocr=" + config.stages().ingest().ocr().enabled());
        String jobId = HashUtils.jobId(config.input(), configFingerprint);

        // 注册 shutdown hook（优雅停机）
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { engine.close(); } catch (Exception ignored) {}
        }));

        // ── 执行 ──
        var stats = engine.execute(jobId, inputPath, outputPath);

        System.out.println();
        System.out.println("════════════════════════════════════");
        System.out.println("  清洗完成！");
        System.out.println("  总文件数:   " + stats.totalFiles());
        System.out.println("  成功:       " + stats.succeeded());
        System.out.println("  失败:       " + stats.failed());
        System.out.println("  总 chunk:   " + stats.totalChunks());
        System.out.println("  产出目录:   " + outputPath);
        System.out.println("════════════════════════════════════");
    }

    /** 解析配置文件路径（--config 直接用，--template 从 KNOWLY_HOME/templates/ 加载） */
    private Path resolveConfigPath(String configPath, String templateName) {
        if (configPath != null) return Path.of(configPath);
        String templatesDir = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly") + "/templates";
        return Path.of(templatesDir, templateName + ".yaml");
    }

    /** CLI 进度打印 */
    private void printProgress(PipelineEvent event) {
        if (event instanceof PipelineEvent.PipelineStarted e) {
            System.out.println("\n开始清洗: " + e.totalFiles() + " 个文件\n");
        } else if (event instanceof PipelineEvent.FileStarted e) {
            System.out.print("  处理中: " + e.filePath() + " ... ");
        } else if (event instanceof PipelineEvent.FileCompleted e) {
            System.out.println("✓ (" + e.chunkCount() + " chunks)");
        } else if (event instanceof PipelineEvent.FileFailed e) {
            System.out.println("✗ " + e.error());
        } else if (event instanceof PipelineEvent.PipelineFinished) {
            System.out.println();
        }
    }
}
