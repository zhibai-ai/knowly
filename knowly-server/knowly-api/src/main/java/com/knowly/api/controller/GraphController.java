package com.knowly.api.controller;

import com.knowly.api.common.ApiResponse;
import com.knowly.api.service.GraphService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

/**
 * 图谱构建接口（独立步骤，输入清洗产出的 JSONL）。
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphService graphService;

    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    /** 启动图谱构建 */
    @PostMapping("/build")
    public ApiResponse build(@RequestBody Map<String, String> body) {
        String jsonlPath = body.get("jsonlPath");
        if (jsonlPath == null || jsonlPath.isBlank()) {
            return ApiResponse.error("CONFIG_002", "必须指定 jsonlPath");
        }
        if (graphService.isRunning()) {
            return ApiResponse.error("JOB_001", "已有图谱构建任务在运行中");
        }
        // jobId 在 Controller 同步生成，再交给 @Async 方法异步执行
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        graphService.startBuild(jobId, jsonlPath);
        return ApiResponse.ok(Map.of("jobId", jobId), "图谱构建已启动");
    }

    /** 当前构建状态 */
    @GetMapping("/status")
    public ApiResponse status() {
        return ApiResponse.ok(graphService.getStatus());
    }

    /** 取消构建 */
    @PostMapping("/cancel")
    public ApiResponse cancel() {
        graphService.cancel();
        return ApiResponse.ok(null, "取消请求已发送");
    }
}
