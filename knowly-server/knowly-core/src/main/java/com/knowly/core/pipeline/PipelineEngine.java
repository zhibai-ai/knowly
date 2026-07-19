package com.knowly.core.pipeline;

import com.knowly.common.enums.ProcessStage;
import com.knowly.common.enums.StageStatus;
import com.knowly.common.exception.KnowlyException;
import com.knowly.core.error.ErrorCollector;
import com.knowly.core.event.PipelineEvent;
import com.knowly.core.event.PipelineEventListener;
import com.knowly.core.model.EmbeddedChunk;
import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.ChunkingStrategy;
import com.knowly.core.spi.DedupStrategy;
import com.knowly.core.spi.DocumentParser;
import com.knowly.core.spi.EmbeddingProvider;
import com.knowly.core.spi.LayoutAnalyzer;
import com.knowly.core.spi.StateRepository;
import com.knowly.core.spi.TextCleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流水线引擎——系统的核心。
 *
 * <p>职责：
 * <ol>
 *   <li>扫描 input 目录 → 文件列表</li>
 *   <li>按文档流式处理（per document）：ingest → clean → [embed] → sinks</li>
 *   <li>发布进度事件（观察者模式），供 CLI/Web 订阅</li>
 *   <li>处理失败：单文件失败记录到 ErrorCollector，不中断</li>
 *   <li>持久化状态：每阶段完成更新 StateRepository（断点续跑）</li>
 * </ol>
 *
 * <p>流式处理（per document）：每个文档独立流转完所有阶段，内存占用恒定。
 */
public class PipelineEngine {

    private static final Logger log = LoggerFactory.getLogger(PipelineEngine.class);

    // ── 各阶段的实现组件（由外部注入）──
    private final DocumentParser parser;
    private final LayoutAnalyzer layoutAnalyzer;  // 可为 null
    private final TextCleaner cleaner;
    private final DedupStrategy fileDedup;
    private final DedupStrategy chunkDedup;       // 可为 null
    private final ChunkingStrategy chunking;
    private final EmbeddingProvider embeddingProvider;  // 可为 null（当无向量库 sink 时）
    private final List<ChunkSink> sinks;
    private final StateRepository stateRepository;

    // ── 事件监听器（观察者）──
    private final List<PipelineEventListener> listeners = new CopyOnWriteArrayList<>();

    // ── 运行配置 ──
    private final boolean enableLayoutAnalysis;
    private volatile Path outputDir;  // 由 execute 方法赋值

    public PipelineEngine(Builder builder) {
        this.parser = builder.parser;
        this.layoutAnalyzer = builder.layoutAnalyzer;
        this.cleaner = builder.cleaner;
        this.fileDedup = builder.fileDedup;
        this.chunkDedup = builder.chunkDedup;
        this.chunking = builder.chunking;
        this.embeddingProvider = builder.embeddingProvider;
        this.sinks = builder.sinks;
        this.stateRepository = builder.stateRepository;
        this.enableLayoutAnalysis = builder.enableLayoutAnalysis;
    }

    /** 注册事件监听器 */
    public void addListener(PipelineEventListener listener) {
        listeners.add(listener);
    }

    /**
     * 执行完整流水线。
     *
     * @param jobId    清洗任务 ID
     * @param inputDir 输入目录
     * @param outputDir 输出目录（用于写报告文件）
     * @return 处理结果统计
     */
    public PipelineStats execute(String jobId, Path inputDir, Path outputDir) {
        this.outputDir = outputDir;
        // 不再整体清空产出目录——只清理本次输入文件对应的旧产出
        // 扫描文件
        List<Path> files = scanFiles(inputDir);
        int total = files.size();
        log.info("流水线启动: jobId={}, files={}", jobId, total);
        emit(new PipelineEvent.PipelineStarted(jobId, "knowly", total));

        ErrorCollector errors = new ErrorCollector();
        int succeeded = 0;
        int totalChunks = 0;
        boolean hasEmbeddingSink = sinks.stream().anyMatch(ChunkSink::requiresEmbedding);

        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            try {
                // 清理该文件对应的旧产出（只删自己的，不影响其他文件）
                cleanOldOutputForFile(file);
                // ── 断点续跑检测 ──
                if (stateRepository != null && stateRepository.hasUnfinishedFiles(jobId)) {
                    ProcessStage lastDone = stateRepository.getLastCompletedStage(jobId, file.toString());
                    if (lastDone == ProcessStage.SINK) {
                        log.debug("跳过已完成文件: {}", file);
                        continue;
                    }
                }

                emit(new PipelineEvent.FileStarted(jobId, file.toString(), ""));

                // ── INGEST ──
                RawDocument rawDoc = doIngest(jobId, file, errors);
                if (rawDoc == null) continue;

                emit(new PipelineEvent.StageProgress(jobId, rawDoc.id(),
                        ProcessStage.INGEST, i + 1, total));

                // ── 文件级去重 ──
                if (fileDedup != null) {
                    var dedupResult = fileDedup.check(rawDoc);
                    if (dedupResult.isDuplicate()) {
                        log.debug("文件级去重命中: {} 重复于 {}", file, dedupResult.duplicateOf());
                        continue;  // 跳过重复文件
                    }
                }

                // ── CLEAN ──
                List<TextChunk> chunks = doClean(jobId, rawDoc, errors);
                if (chunks == null || chunks.isEmpty()) continue;

                emit(new PipelineEvent.StageProgress(jobId, rawDoc.id(),
                        ProcessStage.CLEAN, i + 1, total));

                // ── 段落级去重（标记不删）──
                if (chunkDedup != null) {
                    chunkDedup.checkChunks(chunks);  // 标记但不删
                }

                // ── EMBED（仅当有向量库 sink）──
                if (hasEmbeddingSink && embeddingProvider != null) {
                    doEmbed(jobId, chunks, errors);
                    emit(new PipelineEvent.StageProgress(jobId, rawDoc.id(),
                            ProcessStage.EMBED, i + 1, total));
                }

                // ── SINK（并行写入各 sink）──
                doSink(jobId, rawDoc, chunks, errors);

                succeeded++;
                totalChunks += chunks.size();
                emit(new PipelineEvent.FileCompleted(jobId, rawDoc.id(), chunks.size()));

            } catch (KnowlyException e) {
                log.warn("文件处理失败: {}, error={}", file, e.getMessage());
                errors.record(file.toString(), null, "UNKNOWN", e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
                log.warn("文件处理失败: {}, cause={}", file, e.getMessage(), e);
                errors.record(file.toString(), null, "UNKNOWN", "UNEXPECTED", e.getMessage());
            }
        }

        // 关闭所有 sink
        for (ChunkSink sink : sinks) {
            try { sink.close(); } catch (Exception ignored) {}
        }

        PipelineStats stats = new PipelineStats(total, succeeded, errors.failureCount(), totalChunks);
        log.info("流水线完成: jobId={}, {}", jobId, stats);
        emit(new PipelineEvent.PipelineFinished(jobId, succeeded, errors.failureCount(), totalChunks));

        // 产出报告文件
        writeReports(outputDir, stats, errors);

        return stats;
    }

    // ────────────────────────────────────────────────────────────
    // 各阶段处理
    // ────────────────────────────────────────────────────────────

    private RawDocument doIngest(String jobId, Path file, ErrorCollector errors) {
        try {
            markStage(jobId, file, ProcessStage.INGEST, StageStatus.IN_PROGRESS);
            RawDocument doc = parser.parse(file);
            // 版面分析（可选）
            if (enableLayoutAnalysis && layoutAnalyzer != null) {
                var layout = layoutAnalyzer.analyze(file, doc.content());
                doc = new RawDocument(doc.id(), doc.sourcePath(), doc.fileName(), doc.format(),
                        layout.text(), doc.metadata(), doc.contentHash(), doc.status(), doc.images());
            }
            // 如果有提取的图片，复制到产出目录
            if (doc.images() != null && !doc.images().isEmpty()) {
                copyImagesToOutput(doc);
            }
            markStage(jobId, file, ProcessStage.INGEST, StageStatus.SUCCESS);
            return doc;
        } catch (Exception e) {
            markStage(jobId, file, ProcessStage.INGEST, StageStatus.FAILED);
            errors.record(file.toString(), null, ProcessStage.INGEST.name(),
                    e instanceof KnowlyException ke ? ke.getErrorCode() : "PARSE_ERROR",
                    e.getMessage());
            return null;
        }
    }

    private List<TextChunk> doClean(String jobId, RawDocument doc, ErrorCollector errors) {
        try {
            markStage(jobId, Path.of(doc.sourcePath()), ProcessStage.CLEAN, StageStatus.IN_PROGRESS);
            String cleaned = cleaner.clean(doc.content(), doc);
            // 分段
            RawDocument cleanedDoc = new RawDocument(doc.id(), doc.sourcePath(), doc.fileName(),
                    doc.format(), cleaned, doc.metadata(), doc.contentHash(), doc.status());
            List<TextChunk> chunks = chunking.chunk(cleanedDoc);
            markStage(jobId, Path.of(doc.sourcePath()), ProcessStage.CLEAN, StageStatus.SUCCESS);
            return chunks;
        } catch (Exception e) {
            markStage(jobId, Path.of(doc.sourcePath()), ProcessStage.CLEAN, StageStatus.FAILED);
            errors.record(doc.sourcePath(), doc.id(), ProcessStage.CLEAN.name(),
                    e instanceof KnowlyException ke ? ke.getErrorCode() : "CLEAN_ERROR",
                    e.getMessage());
            return null;
        }
    }

    private void doEmbed(String jobId, List<TextChunk> chunks, ErrorCollector errors) {
        // embedding 在 sink 层做（每个向量库 sink 自己调 embeddingProvider）
        // 这里不做——embed 是 sink 的前置步骤，由 PipelineEngine 传 embeddingProvider 给 sink
    }

    private void doSink(String jobId, RawDocument doc, List<TextChunk> chunks, ErrorCollector errors) {
        // 先写文本类 sink（Markdown/JSONL）
        for (ChunkSink sink : sinks) {
            try {
                if (!sink.requiresEmbedding()) {
                    sink.write(chunks);
                }
            } catch (Exception e) {
                errors.record(doc.sourcePath(), doc.id(), ProcessStage.SINK.name(),
                        "SINK_ERROR", "sink=" + sink.type() + ", cause=" + e.getMessage());
            }
        }

        // 再处理向量库 sink（需要 embedding）
        if (embeddingProvider != null) {
            for (ChunkSink sink : sinks) {
                if (!sink.requiresEmbedding()) continue;
                try {
                    // 批量 embed
                    List<String> texts = chunks.stream().map(TextChunk::text).toList();
                    List<float[]> embeddings = embeddingProvider.embedBatch(texts);
                    List<EmbeddedChunk> embedded = new ArrayList<>(chunks.size());
                    for (int i = 0; i < chunks.size(); i++) {
                        TextChunk c = chunks.get(i);
                        embedded.add(new EmbeddedChunk(c.id(), c.text(), embeddings.get(i),
                                c.documentId(), Map.of()));
                    }
                    sink.writeEmbedded(embedded);
                } catch (Exception e) {
                    errors.record(doc.sourcePath(), doc.id(), ProcessStage.SINK.name(),
                            "SINK_ERROR", "sink=" + sink.type() + ", cause=" + e.getMessage());
                }
            }
        }

        markStage(jobId, Path.of(doc.sourcePath()), ProcessStage.SINK, StageStatus.SUCCESS);
    }

    // ────────────────────────────────────────────────────────────
    // 辅助
    // ────────────────────────────────────────────────────────────

    /** 清理指定文件对应的旧产出（只删自己的 .md 和图片，不影响其他文件） */
    private void cleanOldOutputForFile(Path file) {
        if (outputDir == null) return;
        try {
            Path cleanDir = outputDir.resolve("00-clean");
            if (!java.nio.file.Files.exists(cleanDir)) return;

            // 推算该文件会产出的 .md 文件名（与 MarkdownSink 的 sanitizeFileName 逻辑一致）
            String fileName = file.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;

            // 删除旧的 .md 文件
            Path oldMd = cleanDir.resolve(baseName + ".md");
            java.nio.file.Files.deleteIfExists(oldMd);

            // 删除旧的图片目录（如果是图片型 PDF，图片存在 baseName/images 或 images 下）
            // [简化] 不删 images 目录——因为多个文件可能共享同一个 images 目录
            // 图片按文件名前缀区分，但当前命名规则是 p1_1.png（不含文件名），暂不删图片
            log.debug("清理旧产出: {}", oldMd);
        } catch (Exception e) {
            log.warn("清理旧产出失败: {}, cause={}", file, e.getMessage());
        }
    }

    /** 清理整个产出目录（已废弃，保留方法以防需要） */
    @SuppressWarnings("unused")
    private void cleanOutputDir(Path outputDir) {
        Path cleanDir = outputDir.resolve("00-clean");
        Path chunksDir = outputDir.resolve("01-chunks");
        Path reportsDir = outputDir.resolve("02-reports");
        Path stateDir = outputDir.resolve(".knowly");
        for (Path dir : List.of(cleanDir, chunksDir, reportsDir, stateDir)) {
            if (java.nio.file.Files.exists(dir)) {
                try (var stream = java.nio.file.Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())  // 先删文件再删目录
                            .forEach(p -> {
                                try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                } catch (Exception e) {
                    log.warn("清理目录失败: {}, cause={}", dir, e.getMessage());
                }
            }
        }
        log.info("旧产出已清理: {}", outputDir);
    }

    /**
     * 把提取的图片从临时目录复制到产出目录。
     * 临时目录通过系统属性 knowly.image.tempdir 传递。
     */
    private void copyImagesToOutput(RawDocument doc) {
        if (doc.images() == null || doc.images().isEmpty()) return;
        try {
            // 找最新的临时图片目录
            Path tempBase = null;
            try (var tempDirs = java.nio.file.Files.list(Path.of("/tmp"))) {
                var opt = tempDirs.filter(p -> p.getFileName().toString().startsWith("knowly-images-"))
                        .max((a, b) -> {
                            try {
                                return Long.compare(
                                        java.nio.file.Files.getLastModifiedTime(a).toMillis(),
                                        java.nio.file.Files.getLastModifiedTime(b).toMillis());
                            } catch (java.io.IOException ex) {
                                return 0;
                            }
                        });
                if (opt.isEmpty()) return;
                tempBase = opt.get();
            }

            // 把图片复制到产出目录的 00-clean/images/
            // outputDir 从 execute 方法的参数传入，用成员变量传递
            Path targetImgDir = outputDir.resolve("00-clean").resolve("images");
            java.nio.file.Files.createDirectories(targetImgDir);

            for (var img : doc.images()) {
                Path src = tempBase.resolve(img.filePath());
                if (java.nio.file.Files.exists(src)) {
                    Path target = targetImgDir.resolve(Path.of(img.filePath()).getFileName().toString());
                    java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.info("图片复制完成: {} 张 → {}", doc.images().size(), targetImgDir);

        } catch (Exception e) {
            log.warn("图片复制失败: {}", e.getMessage());
        }
    }

    /** 写报告文件到 outputDir/02-reports/ */
    private void writeReports(Path outputDir, PipelineStats stats, ErrorCollector errors) {
        try {
            Path reportDir = outputDir.resolve("02-reports");
            Files.createDirectories(reportDir);

            // processing-report.json
            String processingReport = """
                    {
                      "total_files": %d,
                      "succeeded": %d,
                      "failed": %d,
                      "total_chunks": %d
                    }
                    """.formatted(stats.totalFiles(), stats.succeeded(), stats.failed(), stats.totalChunks());
            Files.writeString(reportDir.resolve("processing-report.json"), processingReport);

            // error-report.json
            if (errors.hasFailures()) {
                var sb = new StringBuilder("[\n");
                var failures = errors.getFailures();
                for (int i = 0; i < failures.size(); i++) {
                    var f = failures.get(i);
                    if (i > 0) sb.append(",\n");
                    sb.append("  {\"file\": \"").append(f.filePath())
                      .append("\", \"stage\": \"").append(f.stage())
                      .append("\", \"errorCode\": \"").append(f.errorCode())
                      .append("\", \"error\": \"").append(f.message().replace("\"", "\\\""))
                      .append("\"}");
                }
                sb.append("\n]");
                Files.writeString(reportDir.resolve("error-report.json"), sb.toString());
            } else {
                Files.writeString(reportDir.resolve("error-report.json"), "{\"failed\": 0}");
            }
            log.info("报告已产出: {}", reportDir);
        } catch (Exception e) {
            log.warn("报告产出失败: {}", e.getMessage());
        }
    }

    /** 支持的文档扩展名白名单 */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".pdf", ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx",
            ".txt", ".md", ".html", ".htm", ".rtf", ".odt",
            ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".gif"
    );

    /** 扫描目录下的所有文档文件（递归，按扩展名过滤） */
    private List<Path> scanFiles(Path inputDir) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(inputDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> !f.getFileName().toString().startsWith("."))
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .forEach(files::add);
        } catch (Exception e) {
            log.error("扫描目录失败: {}", inputDir, e);
        }
        return files;
    }

    private void markStage(String jobId, Path file, ProcessStage stage, StageStatus status) {
        if (stateRepository != null) {
            stateRepository.markStage(jobId, file.toString(), null, null, stage, status);
        }
    }

    private void emit(PipelineEvent event) {
        for (PipelineEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.warn("事件监听器异常: {}", e.getMessage());
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // 结果 & Builder
    // ────────────────────────────────────────────────────────────

    /** 流水线运行统计 */
    public record PipelineStats(int totalFiles, int succeeded, int failed, int totalChunks) {}

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DocumentParser parser;
        private LayoutAnalyzer layoutAnalyzer;
        private TextCleaner cleaner;
        private DedupStrategy fileDedup;
        private DedupStrategy chunkDedup;
        private ChunkingStrategy chunking;
        private EmbeddingProvider embeddingProvider;
        private List<ChunkSink> sinks = new ArrayList<>();
        private StateRepository stateRepository;
        private boolean enableLayoutAnalysis = false;

        public Builder parser(DocumentParser p) { this.parser = p; return this; }
        public Builder layoutAnalyzer(LayoutAnalyzer l) { this.layoutAnalyzer = l; return this; }
        public Builder cleaner(TextCleaner c) { this.cleaner = c; return this; }
        public Builder fileDedup(DedupStrategy d) { this.fileDedup = d; return this; }
        public Builder chunkDedup(DedupStrategy d) { this.chunkDedup = d; return this; }
        public Builder chunking(ChunkingStrategy c) { this.chunking = c; return this; }
        public Builder embeddingProvider(EmbeddingProvider e) { this.embeddingProvider = e; return this; }
        public Builder addSink(ChunkSink s) { this.sinks.add(s); return this; }
        public Builder stateRepository(StateRepository s) { this.stateRepository = s; return this; }
        public Builder enableLayoutAnalysis(boolean b) { this.enableLayoutAnalysis = b; return this; }

        public PipelineEngine build() {
            if (parser == null) throw new IllegalStateException("parser 必须设置");
            if (cleaner == null) throw new IllegalStateException("cleaner 必须设置");
            if (chunking == null) throw new IllegalStateException("chunking 必须设置");
            if (sinks.isEmpty()) throw new IllegalStateException("至少需要一个 sink");
            return new PipelineEngine(this);
        }
    }
}
