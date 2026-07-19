package com.knowly.api.controller;

import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 本机文件浏览接口。用户在 Web 上选择输入文件/目录。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    @GetMapping("/browse")
    public Map<String, Object> browse(@RequestParam(defaultValue = "/") String path) {
        Path dir = Path.of(path);
        if (!Files.isDirectory(dir)) {
            return Map.of("code", -1, "message", "路径不存在或非目录: " + path);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(f -> {
                try {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("name", f.getFileName().toString());
                    entry.put("path", f.toString());
                    entry.put("isDirectory", Files.isDirectory(f));
                    entry.put("size", Files.isDirectory(f) ? 0L : Files.size(f));
                    entries.add(entry);
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            return Map.of("code", -1, "message", "目录读取失败: " + path + ", " + e.getMessage());
        }
        // 目录排前
        entries.sort((a, b) -> {
            int dirCompare = Boolean.compare(!(boolean) a.get("isDirectory"), !(boolean) b.get("isDirectory"));
            return dirCompare != 0 ? dirCompare : ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", Map.of("path", path, "entries", entries));
        return result;
    }
}
