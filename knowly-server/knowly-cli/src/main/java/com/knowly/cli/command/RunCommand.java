package com.knowly.cli.command;

import com.knowly.clean.chunking.StructureAwareChunking;
import com.knowly.clean.cleaner.DefaultTextCleaner;
import com.knowly.clean.dedup.HashDedupStrategy;
import com.knowly.clean.dedup.MinHashDedupStrategy;
import com.knowly.core.event.PipelineEvent;
import com.knowly.core.event.PipelineEventListener;
import com.knowly.core.pipeline.PipelineEngine;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.StateRepository;
import com.knowly.core.state.SqliteStateRepository;
import com.knowly.embed.sink.jsonl.JsonlSink;
import com.knowly.embed.sink.markdown.MarkdownSink;
import com.knowly.ingest.CompositeParser;
import com.knowly.ingest.ocr.TesseractOcrEngine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * run 命令——组装并执行清洗流水线。
 *
 * <p>解析参数 → 加载 YAML 配置 → 实例化各组件 → 构建 PipelineEngine → 执行。
 */
public class RunCommand {

    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    @SuppressWarnings("unchecked")
    public void execute(String[] args) throws Exception {
        // 解析命令行参数
        String configPath = null;
        String templateName = null;
        String inputDir = null;
        String outputDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configPath = args[++i];
                case "--template" -> templateName = args[++i];
                case "--input" -> inputDir = args[++i];
                case "--output" -> outputDir = args[++i];
            }
        }

        if (configPath == null && templateName == null) {
            System.err.println("错误：必须指定 --config <文件> 或 --template <名称>");
            System.exit(1);
        }

        // 加载配置（YAML）
        Map<String, Object> config;
        if (configPath != null) {
            config = loadYaml(configPath);
        } else {
            String templatesDir = System.getenv().getOrDefault("KNOWLY_HOME",
                    System.getProperty("user.home") + "/.knowly") + "/templates";
            config = loadYaml(templatesDir + "/" + templateName + ".yaml");
        }

        // 命令行参数覆盖配置
        if (inputDir == null) inputDir = getNested(config, "pipeline", "input");
        if (outputDir == null) outputDir = getNested(config, "pipeline", "output");
        if (inputDir == null || outputDir == null) {
            System.err.println("错误：必须指定 --input 和 --output（或在配置中定义）");
            System.exit(1);
        }

        Path inputPath = Path.of(inputDir);
        Path outputPath = Path.of(outputDir);

        log.info("知了清洗启动: input={}, output={}", inputPath, outputPath);

        // ── 组装各组件 ──
        // OCR 引擎
        var ocrEngine = new TesseractOcrEngine(List.of("chi_sim", "eng"));
        // 复合解析器（PDF→PdfDocumentParser，其他→Tika，按优先级路由）
        var compositeParser = new CompositeParser(ocrEngine, List.of("chi_sim", "eng"));

        // 清洗器
        var cleaner = new DefaultTextCleaner();

        // 去重
        var fileDedup = new HashDedupStrategy();
        var chunkDedup = new MinHashDedupStrategy(0.85);

        // 分段
        var chunking = new StructureAwareChunking(500, 50, 50);

        // Sink——根据配置决定产出哪些
        List<ChunkSink> sinks = new ArrayList<>();
        // 默认：Markdown + JSONL
        sinks.add(new MarkdownSink(outputPath.resolve("00-clean"), true, false));
        sinks.add(new JsonlSink(outputPath.resolve("01-chunks").resolve("chunks.jsonl")));

        // 向量库 sink（可选——取决于配置和 API key）
        String dashscopeKey = System.getenv("DASHSCOPE_API_KEY");
        boolean hasEmbedding = dashscopeKey != null && !dashscopeKey.isBlank();
        // [待完善] 根据配置里的 sinks 列表动态创建 QdrantSink/PgVectorSink

        // 状态存储（SQLite）
        Path stateDbPath = outputPath.resolve(".knowly").resolve("knowly.db");
        StateRepository stateRepo = new SqliteStateRepository(stateDbPath);

        // ── 构建 PipelineEngine ──
        var engineBuilder = PipelineEngine.builder()
                .parser(compositeParser)
                .cleaner(cleaner)
                .fileDedup(fileDedup)
                .chunkDedup(chunkDedup)
                .chunking(chunking)
                .stateRepository(stateRepo)
                .enableLayoutAnalysis(false);  // [待完善] 版面分析 RuleBasedLayoutAnalyzer 需要 PDFBox 坐标提取，暂关

        for (ChunkSink sink : sinks) {
            engineBuilder.addSink(sink);
        }

        // 如果有 embedding 配置，加 embedding provider
        if (hasEmbedding) {
            engineBuilder.embeddingProvider(
                    new com.knowly.embed.dashscope.DashScopeEmbeddingProvider(dashscopeKey));
        }

        var engine = engineBuilder.build();

        // 注册 CLI 进度监听器（打印到终端）
        engine.addListener(this::printProgress);

        // 生成 jobId
        String jobId = UUID.randomUUID().toString().substring(0, 8);

        // 执行
        var stats = engine.execute(jobId, inputPath, outputPath);

        // 打印统计
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
        // StageProgress 不打印（减少噪声）
    }

    // ── 工具方法 ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(String path) throws Exception {
        var loaderOptions = new org.yaml.snakeyaml.LoaderOptions();
        Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));  // SafeConstructor 防反序列化攻击
        try (var is = java.nio.file.Files.newInputStream(java.nio.file.Path.of(path))) {
            return yaml.load(is);
        }
    }

    @SuppressWarnings("unchecked")
    private String getNested(Map<String, Object> config, String... keys) {
        Map<String, Object> current = config;
        for (int i = 0; i < keys.length - 1; i++) {
            Object val = current.get(keys[i]);
            if (!(val instanceof Map)) return null;
            current = (Map<String, Object>) val;
        }
        Object result = current.get(keys[keys.length - 1]);
        return result != null ? result.toString() : null;
    }
}
