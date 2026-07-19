package com.knowly.api.service;

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
import com.knowly.ingest.ocr.TesseractOcrEngine;
import com.knowly.ingest.CompositeParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 清洗任务服务。负责组装 PipelineEngine 并异步执行。
 *
 * <p>同一时间只允许一个清洗任务（互斥）。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private volatile String currentJobId = null;
    private volatile boolean isRunning = false;
    private volatile PipelineEngine.PipelineStats lastStats = null;

    private final List<PipelineEventListener> sseListeners = new CopyOnWriteArrayList<>();

    /**
     * 异步启动清洗任务。
     *
     * @return jobId（启动失败返回 null）
     */
    @Async
    public void startJob(String jobId, String inputPath, String outputPath, List<String> sinkTypes) {
        log.info("清洗任务启动: jobId={}, input={}, output={}, sinks={}", jobId, inputPath, outputPath, sinkTypes);

        try {
            var ocrEngine = new TesseractOcrEngine(List.of("chi_sim", "eng"));
            var compositeParser = new CompositeParser(ocrEngine, List.of("chi_sim", "eng"));

            var cleaner = new DefaultTextCleaner();
            var fileDedup = new HashDedupStrategy();
            var chunkDedup = new MinHashDedupStrategy(0.85);
            var chunking = new StructureAwareChunking(500, 50, 50);

            Path outputDir = Path.of(outputPath);
            List<ChunkSink> sinks = new ArrayList<>();

            // 根据用户选择的 sink 类型动态创建
            boolean needsEmbedding = false;
            for (String sinkType : sinkTypes) {
                switch (sinkType) {
                    case "markdown":
                        sinks.add(new MarkdownSink(outputDir.resolve("00-clean"), true, false));
                        break;
                    case "jsonl":
                        sinks.add(new JsonlSink(outputDir.resolve("01-chunks").resolve("chunks.jsonl")));
                        break;
                    case "qdrant":
                        sinks.add(new com.knowly.embed.sink.qdrant.QdrantSink(
                                "localhost", 6333, "knowly_chunks", 1024, 100));
                        needsEmbedding = true;
                        break;
                    case "pgvector":
                        sinks.add(new com.knowly.embed.sink.pgvector.PgVectorSink(
                                "localhost", 5433, "knowly",
                                "knowly", "knowly_dev",
                                "knowledge_chunks", 1024));
                        needsEmbedding = true;
                        break;
                    default:
                        log.warn("未知 sink 类型: {}", sinkType);
                }
            }
            if (sinks.isEmpty()) {
                sinks.add(new MarkdownSink(outputDir.resolve("00-clean"), true, false));
                sinks.add(new JsonlSink(outputDir.resolve("01-chunks").resolve("chunks.jsonl")));
            }

            Path stateDbPath = outputDir.resolve(".knowly").resolve("knowly.db");
            StateRepository stateRepo = new SqliteStateRepository(stateDbPath);

            var engineBuilder = PipelineEngine.builder()
                    .parser(compositeParser)
                    .cleaner(cleaner)
                    .fileDedup(fileDedup)
                    .chunkDedup(chunkDedup)
                    .chunking(chunking)
                    .stateRepository(stateRepo)
                    .enableLayoutAnalysis(false);

            for (ChunkSink sink : sinks) {
                engineBuilder.addSink(sink);
            }

            // 只有需要 embedding 的 sink 时才创建 embedding provider
            if (needsEmbedding) {
                String dashscopeKey = getApiKeyFromConfig();
                if (dashscopeKey == null || dashscopeKey.isBlank()) {
                    dashscopeKey = System.getenv("DASHSCOPE_API_KEY");
                }
                if (dashscopeKey != null && !dashscopeKey.isBlank()) {
                    engineBuilder.embeddingProvider(
                            new com.knowly.embed.dashscope.DashScopeEmbeddingProvider(dashscopeKey));
                } else {
                    log.warn("需要 embedding 但未配置 DashScope API Key，向量库 sink 将无数据写入");
                }
            }

            var engine = engineBuilder.build();

            // 注册 SSE 监听器（转发事件给 SSE 客户端）
            engine.addListener(event -> {
                for (PipelineEventListener listener : sseListeners) {
                    try {
                        listener.onEvent(event);
                    } catch (Exception ignored) {}
                }
            });

            // 执行
            isRunning = true;
            currentJobId = jobId;
            var stats = engine.execute(jobId, Path.of(inputPath), outputDir);
            lastStats = stats;

        } catch (Exception e) {
            log.error("清洗任务异常: jobId={}, cause={}", jobId, e.getMessage(), e);
        } finally {
            isRunning = false;
        }
    }

    /** 注册 SSE 事件监听器 */
    public void addSseListener(PipelineEventListener listener) {
        sseListeners.add(listener);
    }

    /** 移除 SSE 事件监听器 */
    public void removeSseListener(PipelineEventListener listener) {
        sseListeners.remove(listener);
    }

    public String getCurrentJobId() { return currentJobId; }
    public boolean isRunning() { return isRunning; }
    public PipelineEngine.PipelineStats getLastStats() { return lastStats; }

    /**
     * 检查是否有未完成任务（用于断点续跑提示）。
     */
    public boolean hasUnfinished() {
        return isRunning;
    }

    /**
     * 创建 jobId。
     */
    public String createJobId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 从 app_config 表读 API Key */
    private String getApiKeyFromConfig() {
        String knowlyHome = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly");
        String dbPath = java.nio.file.Path.of(knowlyHome, "knowly.db").toString();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM app_config WHERE key = ?");
            ps.setString(1, "dashscope_api_key");
            java.sql.ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
