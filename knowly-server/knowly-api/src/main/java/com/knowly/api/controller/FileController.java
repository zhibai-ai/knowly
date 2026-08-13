package com.knowly.api.controller;

import com.knowly.api.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 本机文件浏览接口。用户在 Web 上选择输入文件/目录。
 *
 * <p>路径安全：normalize 防穿越。v0.1 本机单用户，允许浏览任意本机目录，
 * 但禁止含有 {@code ..} 穿越的路径被规范化后越界读取。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    /** 返回用户主目录（作为目录浏览的跨平台默认起点） */
    @GetMapping("/home")
    public ApiResponse home() {
        String home = System.getProperty("user.home");
        return ApiResponse.ok(Map.of("path", home));
    }

    @GetMapping("/browse")
    public ApiResponse browse(@RequestParam(defaultValue = "/") String path) {
        // normalize 防穿越（如 ../ 会被规整为真实绝对路径）
        Path dir;
        try {
            dir = Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return ApiResponse.error("SEC_003", "非法路径: " + path);
        }
        if (!Files.isDirectory(dir)) {
            return ApiResponse.error("CONFIG_001", "路径不存在或非目录: " + dir);
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
            return ApiResponse.error("CONFIG_001", "目录读取失败: " + dir + ", " + e.getMessage());
        }
        // 目录排前
        entries.sort((a, b) -> {
            int dirCompare = Boolean.compare(!(boolean) a.get("isDirectory"), !(boolean) b.get("isDirectory"));
            return dirCompare != 0 ? dirCompare : ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        return ApiResponse.ok(Map.of("path", dir.toString(), "entries", entries));
    }
}
