package com.knowly.core.config;

import java.nio.file.Path;
import java.util.List;

/**
 * 流水线配置（强类型）。由 {@link ConfigLoader} 从 YAML 三层合并后产出。
 *
 * <p>这是"配置驱动"的根基——所有组件实例化、参数取值都从本对象读取，
 * 禁止业务代码里硬编码参数。YAML schema 见后端开发文档 §8。
 *
 * <p>不可变 record，加载后即可安全共享。
 *
 * @param name           流水线名称（必填）
 * @param input          输入目录（命令行可覆盖）
 * @param output         输出目录（命令行可覆盖）
 * @param state          状态存储配置（断点续跑用）
 * @param stages         各阶段配置
 * @param sinks          产出目标列表（可多选）
 * @param errorHandling  错误处理策略
 * @param concurrency    并发模型配置
 */
public record PipelineConfig(
        String name,
        String input,
        String output,
        StateConfig state,
        StagesConfig stages,
        List<SinkConfig> sinks,
        ErrorHandlingConfig errorHandling,
        ConcurrencyConfig concurrency
) {

    /** 状态存储配置 */
    public record StateConfig(String store, Path path) {}

    /** 各阶段配置 */
    public record StagesConfig(IngestStage ingest, CleanStage clean, EmbedStage embed) {}

    /** 摄取阶段 */
    public record IngestStage(
            List<String> parsers,
            String layoutAnalysis,
            OcrConfig ocr,
            String headingDictionary
    ) {}

    /** OCR 配置 */
    public record OcrConfig(boolean enabled, List<String> languages, int minCharsPerPage) {}

    /** 清洗阶段 */
    public record CleanStage(
            String encoding,
            NormalizeConfig normalize,
            DedupConfig dedup,
            ChunkingConfig chunking
    ) {}

    /** 规范化配置（全角/繁简/空白） */
    public record NormalizeConfig(
            boolean fullwidthToHalfwidth,
            boolean traditionalToSimplified,
            boolean trimWhitespace
    ) {}

    /** 去重配置 */
    public record DedupConfig(
            FileLevelDedup fileLevel,
            ParagraphLevelDedup paragraphLevel
    ) {}

    public record FileLevelDedup(boolean enabled, String method) {}

    public record ParagraphLevelDedup(boolean enabled, String method, double threshold) {}

    /** 分段配置 */
    public record ChunkingConfig(String strategy, int maxSize, int overlap, int minSize) {}

    /** 向量化阶段 */
    public record EmbedStage(
            String provider,
            String model,
            int dimensions,
            String apiKey,
            int batchSize,
            int qpsLimit
    ) {}

    /** 错误处理 */
    public record ErrorHandlingConfig(String onFileFailure, RetryConfig retry) {}

    public record RetryConfig(int max, String backoff, boolean jitter) {}

    /** 并发模型（有界队列 + 阶段线程池） */
    public record ConcurrencyConfig(
            int ingestThreads,
            int cleanThreads,
            int embedThreads,
            int sinkThreads,
            int queueCapacity
    ) {}
}
