package com.knowly.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowly.common.enums.BlockType;
import com.knowly.common.enums.ParseStatus;
import com.knowly.common.enums.ProcessStage;
import com.knowly.common.exception.KnowlyException;
import com.knowly.common.exception.ParseException;
import com.knowly.common.exception.ErrorCode;
import com.knowly.core.config.PipelineConfig;
import com.knowly.core.model.ChunkMetadata;
import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.ChunkingStrategy;
import com.knowly.core.spi.DocumentParser;
import com.knowly.core.spi.TextCleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PipelineEngine} 集成测试。
 *
 * <p>用 fake 组件（不依赖 Tika/OCR/embed）验证引擎的核心行为：
 * 失败隔离、并发正确性、断点续跑跳过、空输入、产出统计。
 */
class PipelineEngineTest {

    @TempDir
    Path tempDir;

    // ── fake 组件 ──

    /** 可控解析器：指定文件名解析失败，其余返回固定文本 */
    static class FakeParser implements DocumentParser {
        final java.util.Set<String> failFiles;
        final AtomicInteger parseCount = new AtomicInteger(0);

        FakeParser(java.util.Set<String> failFiles) {
            this.failFiles = failFiles;
        }

        @Override
        public boolean supports(String fileName, String mimeType) {
            return true;
        }

        @Override
        public RawDocument parse(Path filePath) {
            parseCount.incrementAndGet();
            String name = filePath.getFileName().toString();
            if (failFiles.contains(name)) {
                throw new ParseException(ErrorCode.PARSE_001, "模拟解析失败", "file=" + name);
            }
            String content = "标题\n" + name + " 的正文内容，足够长的中文段落用于测试分段。";
            return new RawDocument("doc-" + name, filePath.toString(), name, "txt",
                    content, null, "hash-" + name, ParseStatus.SUCCESS, null);
        }
    }

    /** 简单分段器：把文档按行切成 chunk */
    static class LineChunking implements ChunkingStrategy {
        @Override
        public List<TextChunk> chunk(RawDocument doc) {
            List<TextChunk> chunks = new ArrayList<>();
            String[] lines = doc.content().split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].isBlank()) continue;
                chunks.add(new TextChunk(doc.id() + "#" + i, doc.id(), doc.sourcePath(),
                        lines[i], i, new ChunkMetadata(lines[i], 1, 0, lines[i].length(),
                                BlockType.PARAGRAPH, List.of(), List.of())));
            }
            return chunks;
        }
    }

    /** 原样返回的清洗器 */
    static class NoopCleaner implements TextCleaner {
        @Override
        public String clean(String rawText, RawDocument source) {
            return rawText;
        }
    }

    /** 记录写入的 sink（用于断言） */
    static class RecordingSink implements ChunkSink {
        final List<TextChunk> written = new ArrayList<>();
        final AtomicInteger writeCalls = new AtomicInteger(0);

        @Override
        public String type() { return "recording"; }
        @Override
        public boolean requiresEmbedding() { return false; }
        @Override
        public void write(List<TextChunk> chunks) {
            written.addAll(chunks);
            writeCalls.incrementAndGet();
        }
        @Override
        public void deleteByDocument(String documentId) {}
        @Override
        public void close() {}
    }

    // ── 测试用例 ──

    @Test
    void should_isolate_single_file_failure_and_continue() throws Exception {
        // 3 个文件，其中 1 个解析失败 → 另两个应正常处理
        Path f1 = createFile("ok1.txt", "内容一");
        Path f2 = createFile("bad.txt", "内容");
        Path f3 = createFile("ok2.txt", "内容二");

        FakeParser parser = new FakeParser(java.util.Set.of("bad.txt"));
        RecordingSink sink = new RecordingSink();

        PipelineEngine engine = PipelineEngine.builder()
                .parser(parser)
                .cleaner(new NoopCleaner())
                .chunking(new LineChunking())
                .addSink(sink)
                .build();

        var stats = engine.execute("job-test", tempDir, tempDir);

        // 失败隔离：成功 2，失败 1
        assertThat(stats.totalFiles()).isEqualTo(3);
        assertThat(stats.succeeded()).isEqualTo(2);
        assertThat(stats.failed()).isEqualTo(1);
        // sink 收到 2 个成功文件的 chunk
        assertThat(sink.writeCalls.get()).isEqualTo(2);
    }

    @Test
    void should_handle_empty_input_directory() throws Exception {
        // 空目录 → 0 文件，不报错
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);

        FakeParser parser = new FakeParser(java.util.Set.of());
        RecordingSink sink = new RecordingSink();

        PipelineEngine engine = PipelineEngine.builder()
                .parser(parser)
                .cleaner(new NoopCleaner())
                .chunking(new LineChunking())
                .addSink(sink)
                .build();

        var stats = engine.execute("job-empty", emptyDir, tempDir);

        assertThat(stats.totalFiles()).isEqualTo(0);
        assertThat(stats.succeeded()).isEqualTo(0);
        assertThat(sink.writeCalls.get()).isEqualTo(0);
    }

    @Test
    void should_use_concurrent_path_when_concurrency_configured() throws Exception {
        // 并发配置 → 走 executeConcurrent；验证多文件都能处理完
        createFile("c1.txt", "内容");
        createFile("c2.txt", "内容");
        createFile("c3.txt", "内容");

        FakeParser parser = new FakeParser(java.util.Set.of());
        RecordingSink sink = new RecordingSink();

        PipelineConfig.ConcurrencyConfig conc = new PipelineConfig.ConcurrencyConfig(
                4, 2, 2, 2, 100);

        PipelineEngine engine = PipelineEngine.builder()
                .parser(parser)
                .cleaner(new NoopCleaner())
                .chunking(new LineChunking())
                .addSink(sink)
                .concurrency(conc)
                .build();

        var stats = engine.execute("job-concurrent", tempDir, tempDir);

        assertThat(stats.succeeded()).isEqualTo(3);
        assertThat(stats.failed()).isEqualTo(0);
        assertThat(sink.writeCalls.get()).isEqualTo(3);
    }

    @Test
    void should_skip_already_completed_files_on_resume() throws Exception {
        // 断点续跑：状态库里已有 SINK SUCCESS 的文件应跳过
        createFile("done.txt", "内容");
        createFile("todo.txt", "内容");

        // 用真实 SqliteStateRepository 模拟断点
        Path dbPath = tempDir.resolve(".knowly/knowly.db");
        com.knowly.core.state.SqliteStateRepository state =
                new com.knowly.core.state.SqliteStateRepository(dbPath);
        // 预置 done.txt 的 INGEST/CLEAN/SINK 为 SUCCESS（clean-only 场景，无 EMBED 阶段）。
        // 修复后 isAlreadyCompleted 只检查实际启用的阶段，EMBED 不在启用列表时不阻塞。
        for (ProcessStage stage : new ProcessStage[]{
                ProcessStage.INGEST, ProcessStage.CLEAN, ProcessStage.SINK}) {
            state.markStage("job-resume", tempDir.resolve("done.txt").toString(),
                    "hash-done", "doc-done", stage,
                    com.knowly.common.enums.StageStatus.SUCCESS);
        }

        FakeParser parser = new FakeParser(java.util.Set.of());
        RecordingSink sink = new RecordingSink();

        PipelineEngine engine = PipelineEngine.builder()
                .parser(parser)
                .cleaner(new NoopCleaner())
                .chunking(new LineChunking())
                .addSink(sink)
                .stateRepository(state)
                .build();

        var stats = engine.execute("job-resume", tempDir, tempDir);

        // done.txt 跳过，只处理 todo.txt
        assertThat(parser.parseCount.get()).isEqualTo(1);   // 只解析了 1 个
        assertThat(sink.writeCalls.get()).isEqualTo(1);
        assertThat(stats.succeeded()).isEqualTo(1);
    }

    @Test
    void should_write_chunks_to_all_text_sinks() throws Exception {
        createFile("single.txt", "内容");

        RecordingSink sink1 = new RecordingSink();
        RecordingSink sink2 = new RecordingSink();

        PipelineEngine engine = PipelineEngine.builder()
                .parser(new FakeParser(java.util.Set.of()))
                .cleaner(new NoopCleaner())
                .chunking(new LineChunking())
                .addSink(sink1)
                .addSink(sink2)
                .build();

        engine.execute("job-multi-sink", tempDir, tempDir);

        // 两个 sink 都收到 chunk
        assertThat(sink1.written).isNotEmpty();
        assertThat(sink2.written).hasSize(sink1.written.size());
    }

    @Test
    void should_produce_correct_report_files() throws Exception {
        createFile("r1.txt", "内容");
        createFile("r2.txt", "内容");

        PipelineEngine engine = PipelineEngine.builder()
                .parser(new FakeParser(java.util.Set.of()))
                .cleaner(new NoopCleaner())
                .chunking(new LineChunking())
                .addSink(new RecordingSink())
                .build();

        engine.execute("job-report", tempDir, tempDir);

        Path report = tempDir.resolve("02-reports/processing-report.json");
        assertThat(report).exists();
        String content = Files.readString(report);
        assertThat(content).contains("\"total_files\"").contains("\"succeeded\"");
    }

    private Path createFile(String name, String content) throws Exception {
        Path f = tempDir.resolve(name);
        Files.writeString(f, content);
        return f;
    }
}
