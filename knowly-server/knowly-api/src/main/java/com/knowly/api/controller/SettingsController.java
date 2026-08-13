package com.knowly.api.controller;

import com.knowly.api.common.ApiResponse;
import com.knowly.common.util.SecretMasker;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * 设置接口：API Key 配置、修改密码、全局参数。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final String jdbcUrl;

    public SettingsController() {
        String knowlyHome = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly");
        this.jdbcUrl = "jdbc:sqlite:" + Path.of(knowlyHome, "knowly.db");
        initConfigTable();
    }

    /** 返回默认输出目录（跨平台：基于用户主目录，非写死的 Linux 路径） */
    @GetMapping("/default-output")
    public Map<String, Object> getDefaultOutput() {
        // 默认输出到 ~/knowly-output（user.home 跨平台：Windows 是 C:\Users\xxx）
        // 可通过 KNOWLY_HOME 环境变量覆盖
        String base = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly");
        String defaultOutput = Path.of(base, "knowly-output").toString();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("data", defaultOutput);
        return result;
    }

    @GetMapping
    public ApiResponse get() {
        Map<String, String> config = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            ResultSet rs = conn.createStatement().executeQuery("SELECT key, value FROM app_config");
            while (rs.next()) {
                String key = rs.getString("key");
                String value = rs.getString("value");
                // 敏感值脱敏
                if (key.contains("api_key") || key.contains("password")) {
                    value = SecretMasker.maskValue(value);
                }
                config.put(key, value);
            }
        } catch (SQLException ignored) {}
        return ApiResponse.ok(config);
    }

    @PutMapping
    public ApiResponse update(@RequestBody Map<String, String> body) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            for (var entry : body.entrySet()) {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO app_config (key, value, updated_at) VALUES (?, ?, datetime('now'))");
                ps.setString(1, entry.getKey());
                ps.setString(2, entry.getValue());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            return ApiResponse.error("CONFIG_001", "设置更新失败");
        }
        return ApiResponse.ok(null, "设置已更新");
    }

    @PutMapping("/password")
    public ApiResponse updatePassword(@RequestBody Map<String, String> body) {
        String newPassword = body.get("newPassword");
        if (newPassword == null || newPassword.length() < 6) {
            return ApiResponse.error("SEC_001", "密码长度至少 6 位");
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password_hash = ? WHERE username = 'admin'");
            ps.setString(1, newPassword);  // [待完善] bcrypt
            ps.executeUpdate();
        } catch (SQLException e) {
            return ApiResponse.error("SEC_001", "密码修改失败");
        }
        return ApiResponse.ok(null, "密码已修改");
    }

    private void initConfigTable() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.createStatement().execute(
                    "CREATE TABLE IF NOT EXISTS app_config (" +
                    "key TEXT PRIMARY KEY, value TEXT, updated_at TEXT)");
        } catch (SQLException ignored) {}
    }
}
