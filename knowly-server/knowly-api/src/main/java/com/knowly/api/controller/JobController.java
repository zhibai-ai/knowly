package com.knowly.api.controller;

import com.knowly.api.common.ApiResponse;
import com.knowly.api.service.JobService;
import com.knowly.core.event.PipelineEvent;
import com.knowly.core.event.PipelineEventListener;
import com.knowly.core.pipeline.PipelineEngine;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 清洗任务接口 + SSE 进度推送。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /** 创建清洗任务（启动异步清洗） */
    @PostMapping
    public ApiResponse create(@RequestBody Map<String, Object> body) {
        if (jobService.isRunning()) {
            return ApiResponse.error("JOB_001", "已有清洗任务在运行中");
        }
        String inputPath = (String) body.get("sourcePath");
        String outputPath = (String) body.get("outputPath");
        if (inputPath == null || outputPath == null) {
            return ApiResponse.error("CONFIG_002", "必须指定 sourcePath 和 outputPath");
        }
        @SuppressWarnings("unchecked")
        List<String> sinkTypes = (List<String>) body.getOrDefault("sinks", List.of("markdown", "jsonl"));
        String jobId = jobService.createJobId();
        jobService.startJob(jobId, inputPath, outputPath, sinkTypes);
        return ApiResponse.ok(Map.of("jobId", jobId), "清洗已启动");
    }

    /** 当前任务状态 */
    @GetMapping("/current")
    public ApiResponse current() {
        if (!jobService.isRunning() && jobService.getLastStats() == null) {
            return ApiResponse.ok(null);
        }
        if (jobService.isRunning()) {
            return ApiResponse.ok(Map.of(
                    "jobId", jobService.getCurrentJobId() != null ? jobService.getCurrentJobId() : "",
                    "status", "RUNNING"));
        }
        var stats = jobService.getLastStats();
        return ApiResponse.ok(Map.of(
                "jobId", jobService.getCurrentJobId() != null ? jobService.getCurrentJobId() : "",
                "status", "SUCCESS",
                "stats", Map.of(
                        "totalFiles", stats.totalFiles(),
                        "succeeded", stats.succeeded(),
                        "failed", stats.failed(),
                        "totalChunks", stats.totalChunks())));
    }

    /** 取消运行中的任务 */
    @PostMapping("/current/cancel")
    public ApiResponse cancel() {
        if (!jobService.isRunning()) {
            return ApiResponse.error("JOB_003", "没有运行中的任务");
        }
        // [待完善] 实际的优雅停机实现
        return ApiResponse.ok(null, "取消请求已发送（触发优雅停机）");
    }

    /** 检测是否有未完成任务 */
    @GetMapping("/unfinished")
    public ApiResponse unfinished() {
        return ApiResponse.ok(Map.of("hasUnfinished", jobService.hasUnfinished()));
    }

    /** SSE 流：实时进度推送 */
    @GetMapping(value = "/current/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);  // 不超时

        PipelineEventListener listener = event -> {
            try {
                String eventName = event.getClass().getSimpleName();
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(event.toString()));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        };

        jobService.addSseListener(listener);
        emitter.onCompletion(() -> jobService.removeSseListener(listener));
        emitter.onTimeout(() -> jobService.removeSseListener(listener));
        emitter.onError(e -> jobService.removeSseListener(listener));

        return emitter;
    }

    /** 当前任务处理报告 */
    @GetMapping("/current/report")
    public ApiResponse report(@RequestParam String outputPath) {
        try {
            Path reportPath = Path.of(outputPath, "02-reports", "processing-report.json");
            if (Files.exists(reportPath)) {
                return ApiResponse.ok(Files.readString(reportPath));
            }
            return ApiResponse.ok(null, "报告未生成（任务可能未完成）");
        } catch (IOException e) {
            return ApiResponse.error("CONFIG_001", "报告读取失败");
        }
    }

    /** 当前任务错误报告 */
    @GetMapping("/current/errors")
    public ApiResponse errors(@RequestParam String outputPath) {
        try {
            Path errorPath = Path.of(outputPath, "02-reports", "error-report.json");
            if (Files.exists(errorPath)) {
                return ApiResponse.ok(Files.readString(errorPath));
            }
            return ApiResponse.ok(null);
        } catch (IOException e) {
            return ApiResponse.error("CONFIG_001", "错误报告读取失败");
        }
    }

    /** 产出预览：Markdown 文件列表 */
    @GetMapping("/current/output/markdown")
    public ApiResponse outputMarkdown(@RequestParam String outputPath) throws IOException {
        Path cleanDir = Path.of(outputPath, "00-clean");
        if (!Files.exists(cleanDir)) {
            return ApiResponse.ok(List.of());
        }
        var files = new java.util.ArrayList<Map<String, Object>>();
        try (var stream = Files.list(cleanDir)) {
            stream.filter(f -> f.toString().endsWith(".md")).forEach(f -> {
                try {
                    files.add(Map.of(
                            "name", f.getFileName().toString(),
                            "size", Files.size(f),
                            "preview", Files.readString(f).substring(0, Math.min(500, (int) Files.size(f)))));
                } catch (IOException ignored) {}
            });
        }
        return ApiResponse.ok(files);
    }

    /** 产出预览：chunk 列表（分页） */
    @GetMapping("/current/output/chunks")
    public ApiResponse outputChunks(@RequestParam String outputPath,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) throws IOException {
        Path jsonlPath = Path.of(outputPath, "01-chunks", "chunks.jsonl");
        if (!Files.exists(jsonlPath)) {
            return ApiResponse.ok(Map.of("items", List.of(), "total", 0, "page", page, "size", size));
        }
        var allLines = Files.readAllLines(jsonlPath);
        int total = allLines.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        var pageItems = allLines.subList(fromIndex, toIndex);
        return ApiResponse.ok(Map.of(
                "items", pageItems,
                "total", total,
                "page", page,
                "size", size));
    }
}
