package com.knowly.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.knowly.common.enums.ProcessStage;
import com.knowly.common.enums.StageStatus;
import com.knowly.common.exception.KnowlyException;
import com.knowly.core.config.PipelineConfig;
import com.knowly.core.error.ErrorCollector;
import com.knowly.core.event.PipelineEvent;
import com.knowly.core.event.PipelineEventListener;
import com.knowly.core.model.EmbeddedChunk;
import com.knowly.core.model.ChunkMetadata;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流水线引擎——系统的核心。
 *
 * <p>职责：
 * <ol>
 *   <li>扫描 input 目录 → 文件列表</li>
 *   <li>多阶段并发处理：ingest → clean → [embed] → sinks</li>
 *   <li>发布进度事件（观察者模式），供 CLI/Web 订阅</li>
 *   <li>处理失败：单文件失败记录到 ErrorCollector，不中断</li>
 *   <li>持久化状态：每阶段完成更新 StateRepository（断点续跑，文件×阶段粒度）</li>
 *   <li>优雅停机：{@link AutoCloseable} + cancel 信号</li>
 * </ol>
 *
 * <p><b>并发模型</b>（架构文档 §9.1）：文件扫描 → [有界队列1] → Ingest 线程池
 * → [有界队列2] → Clean 线程池 → Sink 线程池。队列满阻塞上游 = 背压。
 *
 * <p><b>流式处理</b>（per document）：每个文档独立流转完所有阶段，内存占用恒定。
 */
public class PipelineEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PipelineEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 优雅停机的等待上限 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    // ── 各阶段的实现组件（由 PipelineAssembler 注入）──
    private final DocumentParser parser;
    private final LayoutAnalyzer layoutAnalyzer;
    private final TextCleaner cleaner;
    private final DedupStrategy fileDedup;
    private final DedupStrategy chunkDedup;
    private final ChunkingStrategy chunking;
    private final EmbeddingProvider embeddingProvider;
    private final List<ChunkSink> sinks;
    private final StateRepository stateRepository;

    // ── 事件监听器（观察者）──
    private final List<PipelineEventListener> listeners = new CopyOnWriteArrayList<>();

    // ── 运行配置 ──
    private final boolean enableLayoutAnalysis;
    private final PipelineConfig.ConcurrencyConfig concurrency;
    private final PipelineConfig config;
    private final boolean embedWithLimit;   // 是否启用 embedding（受 RateLimiter）

    // ── 停机信号 ──
    private volatile boolean shuttingDown = false;

    // ── 线程池（execute 时创建，close 时关闭）──
    private volatile ExecutorService ingestPool;

    /** 图片临时目录（不再硬编码 /tmp，跨平台） */
    private volatile Path imageTempDir;

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
        this.concurrency = builder.concurrency;
        this.config = builder.config;
        this.embedWithLimit = sinks.stream().anyMatch(ChunkSink::requiresEmbedding)
                && embeddingProvider != null;
    }

    /** 注册事件监听器 */
    public void addListener(PipelineEventListener listener) {
        listeners.add(listener);
    }

    /**
     * 请求取消——触发优雅停机。当前进行中的文件处理完（上限 30s）后退出。
     */
    public void cancel() {
        log.info("收到取消信号，开始优雅停机");
        shuttingDown = true;
    }

    /**
     * 执行完整流水线。
     *
     * @param jobId     清洗任务 ID（由调用方派生——同输入同配置复用，保证断点续跑）
     * @param inputDir  输入目录
     * @param outputDir 输出目录
     * @return 处理结果统计
     */
    public PipelineStats execute(String jobId, Path inputDir, Path outputDir) {
        this.outputDir = outputDir;
        boolean hasEmbeddingSink = sinks.stream().anyMatch(ChunkSink::requiresEmbedding);

        // 扫描文件
        List<Path> files = scanFiles(inputDir);
        int total = files.size();
        log.info("流水线启动: jobId={}, files={}, embed={}", jobId, total, embedWithLimit);
        emit(new PipelineEvent.PipelineStarted(jobId, config != null && config.name() != null ? config.name() : "knowly", total));

        ErrorCollector errors = new ErrorCollector();
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger totalChunks = new AtomicInteger(0);

        // 串行回退：若并发配置为 0 或单文件，走简单串行（调试/测试更直观）
        if (concurrency == null || useSerialFallback(files.size())) {
            return executeSerial(jobId, inputDir, outputDir, files, errors, succeeded, totalChunks, hasEmbeddingSink);
        }

        return executeConcurrent(jobId, inputDir, outputDir, files, errors, succeeded, totalChunks, hasEmbeddingSink);
    }

    /** 决定是否走串行回退（小批量或调试场景） */
    private boolean useSerialFallback(int fileCount) {
        if (concurrency.ingestThreads() <= 1) return true;
        return fileCount <= 1;   // 单文件无需并发开销
    }

    // ────────────────────────────────────────────────────────────
    // 串行执行（向后兼容，也是并发池失效时的兜底）
    // ────────────────────────────────────────────────────────────

    private PipelineStats executeSerial(String jobId, Path inputDir, Path outputDir,
                                         List<Path> files, ErrorCollector errors,
                                         AtomicInteger succeeded, AtomicInteger totalChunks,
                                         boolean hasEmbeddingSink) {
        int total = files.size();
        for (int i = 0; i < files.size(); i++) {
            if (shuttingDown) {
                log.info("优雅停机：剩余 {} 个文件未处理（已记录断点）", files.size() - i);
                break;
            }
            Path file = files.get(i);
            try {
                if (isAlreadyCompleted(jobId, file)) {
                    log.debug("断点续跑：跳过已完成文件 {}", file);
                    continue;
                }
                emit(new PipelineEvent.FileStarted(jobId, file.toString(), ""));

                RawDocument rawDoc = doIngest(jobId, file, errors);
                if (rawDoc == null) continue;
                emit(new PipelineEvent.StageProgress(jobId, rawDoc.id(), ProcessStage.INGEST, i + 1, total));

                if (isDuplicateFile(rawDoc)) continue;

                List<TextChunk> chunks = doClean(jobId, rawDoc, errors);
                if (chunks == null || chunks.isEmpty()) continue;
                emit(new PipelineEvent.StageProgress(jobId, rawDoc.id(), ProcessStage.CLEAN, i + 1, total));

                chunks = markChunkDedup(chunks);

                doSink(jobId, rawDoc, chunks, errors, hasEmbeddingSink);

                succeeded.incrementAndGet();
                totalChunks.addAndGet(chunks.size());
                emit(new PipelineEvent.FileCompleted(jobId, rawDoc.id(), chunks.size()));
            } catch (KnowlyException e) {
                log.warn("文件处理失败: {}, error={}", file, e.getMessage());
                errors.record(file.toString(), null, "UNKNOWN", e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
                log.warn("文件处理失败: {}, cause={}", file, e.getMessage(), e);
                errors.record(file.toString(), null, "UNKNOWN", "UNEXPECTED", e.getMessage());
            }
        }
        return finishPipeline(jobId, outputDir, files.size(), succeeded.get(), errors, totalChunks.get());
    }

    // ────────────────────────────────────────────────────────────
    // 并发执行（Semaphore 限流 + 单文档 CompletableFuture 串联）
    //
    // 模型说明：每个文件的处理是 ingest→clean→sink 的完整链。
    // 用一个有界线程池跑所有链 + Semaphore 限制同时在途的文件数（= 背压）。
    // 这比"多阶段独立线程池 + 队列"更简单可靠：
    //   - 无跨阶段队列投递的死锁风险
    //   - 单文档各阶段天然串行（语义正确，sink 依赖 clean 产出）
    //   - 不同文档间并发（文档级并行，已是知了的并发粒度）
    // ────────────────────────────────────────────────────────────

    private PipelineStats executeConcurrent(String jobId, Path inputDir, Path outputDir,
                                             List<Path> files, ErrorCollector errors,
                                             AtomicInteger succeeded, AtomicInteger totalChunks,
                                             boolean hasEmbeddingSink) {
        // 单个共享线程池（文档级并行，每文档内部各阶段串行）
        int parallelism = Math.max(concurrency.ingestThreads(), 1);
        ingestPool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "knowly-worker");
            t.setDaemon(true);
            return t;
        });

        // Semaphore 限制同时在途文档数 = 背压（防止一次性提交全部文件压垮队列/内存）
        // 许可数 = 线程数 × 队列容量系数，保证有界
        int inFlightLimit = parallelism * Math.max(concurrency.queueCapacity() / 10, 2);
        Semaphore inFlight = new Semaphore(inFlightLimit);

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Path file : files) {
                if (shuttingDown) break;
                try {
                    inFlight.acquire();   // 背压：在途文档达上限时阻塞
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                final Path f = file;
                CompletableFuture<Void> future = CompletableFuture
                        .runAsync(() -> processOneDocument(jobId, f, errors, succeeded, totalChunks, hasEmbeddingSink),
                                ingestPool)
                        .whenComplete((v, ex) -> inFlight.release());
                futures.add(future);
            }

            // 等所有文档处理完
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            shutdownPools();
        }

        return finishPipeline(jobId, outputDir, files.size(), succeeded.get(), errors, totalChunks.get());
    }

    /** 处理单个文档的完整链：ingest → clean → [dedup] → sink（单文档内串行） */
    private void processOneDocument(String jobId, Path file, ErrorCollector errors,
                                     AtomicInteger succeeded, AtomicInteger totalChunks,
                                     boolean hasEmbeddingSink) {
        if (shuttingDown) return;
        try {
            if (isAlreadyCompleted(jobId, file)) return;
            emit(new PipelineEvent.FileStarted(jobId, file.toString(), ""));

            RawDocument rawDoc = doIngest(jobId, file, errors);
            if (rawDoc == null) return;
            if (isDuplicateFile(rawDoc)) return;

            List<TextChunk> chunks = doClean(jobId, rawDoc, errors);
            if (chunks == null || chunks.isEmpty()) return;
            chunks = markChunkDedup(chunks);

            doSink(jobId, rawDoc, chunks, errors, hasEmbeddingSink);

            succeeded.incrementAndGet();
            totalChunks.addAndGet(chunks.size());
            emit(new PipelineEvent.FileCompleted(jobId, rawDoc.id(), chunks.size()));
        } catch (KnowlyException e) {
            log.warn("文件处理失败: {}, error={}", file, e.getMessage());
            errors.record(file.toString(), null, "UNKNOWN", e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("文件处理失败: {}, cause={}", file, e.getMessage(), e);
            errors.record(file.toString(), null, "UNKNOWN", "UNEXPECTED", e.getMessage());
        }
    }

    private void shutdownPools() {
        if (ingestPool != null) {
            ingestPool.shutdown();
            try {
                if (!ingestPool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("线程池未在 {}s 内终止，强制关闭", SHUTDOWN_TIMEOUT_SECONDS);
                    ingestPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ingestPool.shutdownNow();
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // 各阶段处理
    // ────────────────────────────────────────────────────────────

    /** 判断文件是否已完成所有阶段（断点续跑跳过） */
    /**
     * 判断文件是否已完成所有应执行的阶段（断点续跑跳过用）。
     *
     * <p>修复关键：不依赖 getLastCompletedStage 的线性 break（它固定遍历
     * INGEST→CLEAN→EMBED→SINK，clean-only 场景 EMBED 永远非 SUCCESS 会导致永远返回非 SINK）。
     * 改为检查本次任务实际需要执行的阶段是否都 SUCCESS：
     * <ul>
     *   <li>INGEST、CLEAN、SINK 必须都 SUCCESS</li>
     *   <li>EMBED 仅当本次启用 embedding（{@link #embedWithLimit}）时才要求 SUCCESS</li>
     * </ul>
     */
    private boolean isAlreadyCompleted(String jobId, Path file) {
        if (stateRepository == null) return false;
        String path = file.toString();
        if (getStageStatusSafe(jobId, path, ProcessStage.INGEST) != StageStatus.SUCCESS) return false;
        if (getStageStatusSafe(jobId, path, ProcessStage.CLEAN) != StageStatus.SUCCESS) return false;
        if (embedWithLimit && getStageStatusSafe(jobId, path, ProcessStage.EMBED) != StageStatus.SUCCESS) {
            return false;
        }
        return getStageStatusSafe(jobId, path, ProcessStage.SINK) == StageStatus.SUCCESS;
    }

    private StageStatus getStageStatusSafe(String jobId, String path, ProcessStage stage) {
        StageStatus s = stateRepository.getStageStatus(jobId, path, stage);
        return s == null ? StageStatus.PENDING : s;
    }

    private boolean isDuplicateFile(RawDocument rawDoc) {
        if (fileDedup == null) return false;
        var dedupResult = fileDedup.check(rawDoc);
        if (dedupResult.isDuplicate()) {
            log.debug("文件级去重命中: {} 重复于 {}", rawDoc.sourcePath(), dedupResult.duplicateOf());
            return true;
        }
        return false;
    }

    /**
     * 段落级去重：根据 checkChunks 返回的结果，给近似重复的 chunk 打标记 tag。
     *
     * <p>文档约定（F2.4）：段落级去重"标记但不删"——保留对照版本，由使用方/人工决定是否合并。
     * 标记方式：在 {@link ChunkMetadata#tags()} 里加 "DUPLICATE_OF:{chunkId}"。
     * 因 TextChunk 是不可变 record，重复的 chunk 需重建。
     *
     * @param chunks 原始 chunk 列表
     * @return 标记后的 chunk 列表（可能与原列表元素不同，但顺序/数量不变）
     */
    private List<TextChunk> markChunkDedup(List<TextChunk> chunks) {
        if (chunkDedup == null || chunks.isEmpty()) {
            return chunks;
        }
        List<com.knowly.core.spi.DedupStrategy.DedupResult> results = chunkDedup.checkChunks(chunks);
        if (results.size() != chunks.size()) {
            // 结果数不匹配（不应发生）→ 不标记，原样返回避免错位
            return chunks;
        }
        List<TextChunk> marked = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk original = chunks.get(i);
            var result = results.get(i);
            if (result.isDuplicate() && result.duplicateOf() != null) {
                String tag = "DUPLICATE_OF:" + result.duplicateOf();
                marked.add(applyTag(original, tag));
            } else {
                marked.add(original);
            }
        }
        return marked;
    }

    /** 给 TextChunk 的 metadata.tags 追加一个 tag（不可变重建） */
    private TextChunk applyTag(TextChunk chunk, String tag) {
        ChunkMetadata oldMeta = chunk.metadata();
        if (oldMeta == null) {
            return chunk;   // 无 metadata 不打标
        }
        List<String> newTags = new ArrayList<>(oldMeta.tags() == null
                ? List.of() : oldMeta.tags());
        if (!newTags.contains(tag)) {
            newTags.add(tag);
        }
        ChunkMetadata newMeta = new ChunkMetadata(
                oldMeta.sectionTitle(), oldMeta.sectionLevel(), oldMeta.startPage(),
                oldMeta.charCount(), oldMeta.blockType(), List.copyOf(newTags),
                oldMeta.origElementIds());
        return new TextChunk(chunk.id(), chunk.documentId(), chunk.sourcePath(),
                chunk.text(), chunk.ordinal(), newMeta);
    }

    private RawDocument doIngest(String jobId, Path file, ErrorCollector errors) {
        try {
            markStage(jobId, file, null, null, ProcessStage.INGEST, StageStatus.IN_PROGRESS);
            RawDocument doc = parser.parse(file);
            if (enableLayoutAnalysis && layoutAnalyzer != null) {
                var layout = layoutAnalyzer.analyze(file, doc.content());
                doc = new RawDocument(doc.id(), doc.sourcePath(), doc.fileName(), doc.format(),
                        layout.text(), doc.metadata(), doc.contentHash(), doc.status(), doc.images());
            }
            if (doc.images() != null && !doc.images().isEmpty()) {
                copyImagesToOutput(doc);
            }
            // ── 修复：填充真实 contentHash/documentId（断点续跑 + 增量更新依赖）──
            markStage(jobId, file, doc.contentHash(), doc.id(), ProcessStage.INGEST, StageStatus.SUCCESS);
            return doc;
        } catch (Exception e) {
            markStage(jobId, file, null, null, ProcessStage.INGEST, StageStatus.FAILED);
            errors.record(file.toString(), null, ProcessStage.INGEST.name(),
                    e instanceof KnowlyException ke ? ke.getErrorCode() : "PARSE_ERROR",
                    e.getMessage());
            return null;
        }
    }

    private List<TextChunk> doClean(String jobId, RawDocument doc, ErrorCollector errors) {
        try {
            markStage(jobId, Path.of(doc.sourcePath()), doc.contentHash(), doc.id(),
                    ProcessStage.CLEAN, StageStatus.IN_PROGRESS);
            String cleaned = cleaner.clean(doc.content(), doc);
            RawDocument cleanedDoc = new RawDocument(doc.id(), doc.sourcePath(), doc.fileName(),
                    doc.format(), cleaned, doc.metadata(), doc.contentHash(), doc.status());
            List<TextChunk> chunks = chunking.chunk(cleanedDoc);
            markStage(jobId, Path.of(doc.sourcePath()), doc.contentHash(), doc.id(),
                    ProcessStage.CLEAN, StageStatus.SUCCESS);
            return chunks;
        } catch (Exception e) {
            markStage(jobId, Path.of(doc.sourcePath()), doc.contentHash(), doc.id(),
                    ProcessStage.CLEAN, StageStatus.FAILED);
            errors.record(doc.sourcePath(), doc.id(), ProcessStage.CLEAN.name(),
                    e instanceof KnowlyException ke ? ke.getErrorCode() : "CLEAN_ERROR",
                    e.getMessage());
            return null;
        }
    }

    private void doSink(String jobId, RawDocument doc, List<TextChunk> chunks,
                        ErrorCollector errors, boolean hasEmbeddingSink) {
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

        // 再处理向量库 sink（需要 embedding，受 RateLimiter 限流）
        if (embedWithLimit) {
            for (ChunkSink sink : sinks) {
                if (!sink.requiresEmbedding()) continue;
                try {
                    List<EmbeddedChunk> embedded = embedChunks(chunks, doc);
                    sink.writeEmbedded(embedded);
                } catch (Exception e) {
                    errors.record(doc.sourcePath(), doc.id(), ProcessStage.SINK.name(),
                            "SINK_ERROR", "sink=" + sink.type() + ", cause=" + e.getMessage());
                }
            }
        }

        markStage(jobId, Path.of(doc.sourcePath()), doc.contentHash(), doc.id(),
                ProcessStage.SINK, StageStatus.SUCCESS);
    }

    /** 批量 embedding（embeddingProvider 内部已有 RateLimiter 限流） */
    private List<EmbeddedChunk> embedChunks(List<TextChunk> chunks, RawDocument doc) {
        List<String> texts = chunks.stream().map(TextChunk::text).toList();
        List<float[]> embeddings = embeddingProvider.embedBatch(texts);
        List<EmbeddedChunk> embedded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk c = chunks.get(i);
            embedded.add(new EmbeddedChunk(c.id(), c.text(), embeddings.get(i),
                    c.documentId(), Map.of()));
        }
        return embedded;
    }

    private PipelineStats finishPipeline(String jobId, Path outputDir, int total,
                                          int succeeded, ErrorCollector errors, int totalChunks) {
        for (ChunkSink sink : sinks) {
            try { sink.close(); } catch (Exception ignored) {}
        }
        PipelineStats stats = new PipelineStats(total, succeeded, errors.failureCount(), totalChunks);
        log.info("流水线完成: jobId={}, {}", jobId, stats);
        emit(new PipelineEvent.PipelineFinished(jobId, succeeded, errors.failureCount(), totalChunks));
        writeReports(outputDir, stats, errors);
        return stats;
    }

    // ────────────────────────────────────────────────────────────
    // 辅助
    // ────────────────────────────────────────────────────────────

    /** 复制图片到产出目录（不再硬编码 /tmp，用 Files.createTempDirectory 跨平台） */
    private void copyImagesToOutput(RawDocument doc) {
        if (doc.images() == null || doc.images().isEmpty()) return;
        if (outputDir == null) return;
        try {
            Path targetImgDir = outputDir.resolve("00-clean").resolve("images");
            Files.createDirectories(targetImgDir);
            for (var img : doc.images()) {
                Path src = Path.of(img.filePath());
                if (Files.exists(src)) {
                    Path target = targetImgDir.resolve(src.getFileName().toString());
                    Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            log.debug("图片复制完成: {} 张 → {}", doc.images().size(), targetImgDir);
        } catch (Exception e) {
            log.warn("图片复制失败: {}", e.getMessage());
        }
    }

    /** 用 Jackson 序列化报告（修复手搓 JSON 的特殊字符问题） */
    private void writeReports(Path outputDir, PipelineStats stats, ErrorCollector errors) {
        try {
            Path reportDir = outputDir.resolve("02-reports");
            Files.createDirectories(reportDir);

            MAPPER.writeValue(reportDir.resolve("processing-report.json").toFile(),
                    Map.of("total_files", stats.totalFiles(),
                            "succeeded", stats.succeeded(),
                            "failed", stats.failed(),
                            "total_chunks", stats.totalChunks()));

            if (errors.hasFailures()) {
                MAPPER.writeValue(reportDir.resolve("error-report.json").toFile(),
                        Map.of("failed", errors.failureCount(),
                                "failures", errors.getFailures()));
            } else {
                MAPPER.writeValue(reportDir.resolve("error-report.json").toFile(),
                        Map.of("failed", 0));
            }
            log.info("报告已产出: {}", reportDir);
        } catch (Exception e) {
            log.warn("报告产出失败: {}", e.getMessage());
        }
    }

    // execute 期间使用的输出目录（由字段持有，便于辅助方法访问）
    private volatile Path outputDir;

    // 重写 execute 入口以设置 outputDir 字段
    // （上面 execute 方法已实现，这里补充字段赋值的辅助方法——见 execute 方法内联使用）

    /** 支持的文档扩展名白名单 */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".pdf", ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx",
            ".txt", ".md", ".html", ".htm", ".rtf", ".odt",
            ".jpg", ".jpeg", ".png", ".tif", ".tiff", ".bmp", ".gif"
    );

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

    private void markStage(String jobId, Path file, String contentHash,
                           String documentId, ProcessStage stage, StageStatus status) {
        if (stateRepository != null) {
            stateRepository.markStage(jobId, file.toString(), contentHash, documentId, stage, status);
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

    /** 序列化事件为 JSON（供 SSE 推送用，修复 toString 不结构化问题） */
    public static String eventToJson(PipelineEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            return "{\"type\":\"" + event.getClass().getSimpleName() + "\"}";
        }
    }

    @Override
    public void close() {
        cancel();
        shutdownPools();
        for (ChunkSink sink : sinks) {
            try { sink.close(); } catch (Exception ignored) {}
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
        private PipelineConfig.ConcurrencyConfig concurrency;
        private PipelineConfig config;

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
        public Builder concurrency(PipelineConfig.ConcurrencyConfig c) { this.concurrency = c; return this; }
        public Builder config(PipelineConfig c) { this.config = c; return this; }

        public PipelineEngine build() {
            if (parser == null) throw new IllegalStateException("parser 必须设置");
            if (cleaner == null) throw new IllegalStateException("cleaner 必须设置");
            if (chunking == null) throw new IllegalStateException("chunking 必须设置");
            if (sinks.isEmpty()) throw new IllegalStateException("至少需要一个 sink");
            return new PipelineEngine(this);
        }
    }
}
