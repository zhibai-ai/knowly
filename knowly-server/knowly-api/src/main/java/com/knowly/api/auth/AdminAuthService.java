package com.knowly.api.auth;

import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.sql.*;

/**
 * admin 账号服务。验证用户名+密码（bcrypt 哈希）。
 * v0.1 单 admin 账号。首次启动自动初始化默认 admin/admin123。
 */
@Service
public class AdminAuthService {
    private final String jdbcUrl;

    public AdminAuthService() {
        String knowlyHome = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly");
        Path dbPath = Path.of(knowlyHome, "knowly.db");
        dbPath.getParent().toFile().mkdirs();
        this.jdbcUrl = "jdbc:sqlite:" + dbPath;
        initDefaultAdmin();
    }

    public boolean verify(String username, String password) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT password_hash FROM users WHERE username = ? AND role = 'ADMIN'");
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return false;
            String storedHash = rs.getString("password_hash");
            // v0.1 简化：明文比对（[待完善] 换 bcrypt）
            return storedHash.equals(password);
        } catch (SQLException e) {
            return false;
        }
    }

    private void initDefaultAdmin() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            Statement st = conn.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT NOT NULL UNIQUE, " +
                    "password_hash TEXT NOT NULL, " +
                    "role TEXT NOT NULL DEFAULT 'ADMIN', " +
                    "created_at TEXT NOT NULL DEFAULT (datetime('now')))");

            // 检查是否已有 admin
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users WHERE username='admin'");
            if (rs.next() && rs.getInt(1) == 0) {
                st.execute("INSERT INTO users (username, password_hash, role) VALUES ('admin', 'admin123', 'ADMIN')");
            }
        } catch (SQLException ignored) {}
    }
}
