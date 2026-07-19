package com.knowly.core.event;

import com.knowly.common.enums.ProcessStage;

/**
 * 流水线进度事件体系（观察者模式）。
 *
 * <p>内核（PipelineEngine）发布事件，CLI 和 Web 各注册 listener 订阅：
 * <ul>
 *   <li>CLI：listener 把事件格式化打印到终端（进度条）</li>
 *   <li>Web（knowly-api）：listener 把事件转 SSE 推给前端</li>
 * </ul>
 * 内核完全不知道有 CLI 还是 Web——这就是观察者模式 + 内核纯净的价值。
 *
 * <p>使用 sealed interface：所有事件类型在编译期穷举，添加新事件类型时编译器强制处理。
 */
public sealed interface PipelineEvent
        permits PipelineEvent.PipelineStarted,
                PipelineEvent.FileStarted,
                PipelineEvent.StageProgress,
                PipelineEvent.FileCompleted,
                PipelineEvent.FileFailed,
                PipelineEvent.PipelineFinished {

    /** 流水线开始 */
    record PipelineStarted(String jobId, String pipelineName, int totalFiles) implements PipelineEvent {}

    /** 某文件开始处理 */
    record FileStarted(String jobId, String filePath, String documentId) implements PipelineEvent {}

    /** 某文件某阶段进度（供进度条展示） */
    record StageProgress(String jobId, String documentId, ProcessStage stage,
                         int current, int total) implements PipelineEvent {}

    /** 某文件处理完成 */
    record FileCompleted(String jobId, String documentId, int chunkCount) implements PipelineEvent {}

    /** 某文件处理失败 */
    record FileFailed(String jobId, String filePath, String error) implements PipelineEvent {}

    /** 流水线全部完成 */
    record PipelineFinished(String jobId, int succeeded, int failed, int totalChunks) implements PipelineEvent {}
}
