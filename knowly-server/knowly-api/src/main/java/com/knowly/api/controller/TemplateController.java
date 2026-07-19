package com.knowly.api.controller;

import com.knowly.api.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 配置模板接口。模板是本地 YAML 文件（$KNOWLY_HOME/templates/*.yaml）。
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private final Path templatesDir;

    public TemplateController() {
        String knowlyHome = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly");
        this.templatesDir = Path.of(knowlyHome, "templates");
        try { Files.createDirectories(templatesDir); } catch (IOException ignored) {}
    }

    @GetMapping
    public ApiResponse list() {
        List<Map<String, Object>> templates = new ArrayList<>();
        try (var stream = Files.list(templatesDir)) {
            stream.filter(f -> f.toString().endsWith(".yaml"))
                    .forEach(f -> {
                        String name = f.getFileName().toString().replace(".yaml", "");
                        Set<String> builtin = Set.of("clean-only", "rag-qdrant", "rag-pgvector");
                        templates.add(Map.of(
                                "name", name,
                                "builtin", builtin.contains(name)
                        ));
                    });
        } catch (IOException e) {
            return ApiResponse.error("CONFIG_001", "模板列表读取失败");
        }
        return ApiResponse.ok(templates);
    }

    @GetMapping("/{name}")
    public ApiResponse load(@PathVariable String name) {
        try {
            Path file = templatesDir.resolve(name + ".yaml");
            String content = Files.readString(file);
            return ApiResponse.ok(Map.of("name", name, "content", content));
        } catch (IOException e) {
            return ApiResponse.error("CONFIG_002", "模板不存在: " + name);
        }
    }

    @PostMapping("/{name}")
    public ApiResponse save(@PathVariable String name, @RequestBody Map<String, String> body) {
        try {
            Path file = templatesDir.resolve(name + ".yaml");
            Files.writeString(file, body.get("content"));
            return ApiResponse.ok(null, "模板已保存: " + name);
        } catch (IOException e) {
            return ApiResponse.error("CONFIG_001", "模板保存失败");
        }
    }

    @DeleteMapping("/{name}")
    public ApiResponse delete(@PathVariable String name) {
        Set<String> builtin = Set.of("clean-only", "rag-qdrant", "rag-pgvector");
        if (builtin.contains(name)) {
            return ApiResponse.error("CONFIG_003", "内置模板不可删除");
        }
        try {
            Files.deleteIfExists(templatesDir.resolve(name + ".yaml"));
            return ApiResponse.ok(null, "模板已删除: " + name);
        } catch (IOException e) {
            return ApiResponse.error("CONFIG_001", "模板删除失败");
        }
    }
}
