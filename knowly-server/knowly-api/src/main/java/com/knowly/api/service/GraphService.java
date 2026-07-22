package com.knowly.api.service;

import com.knowly.graph.GraphBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.Map;

/**
 * 图谱构建服务。异步执行，从 Web 触发。
 *
 * <p>从 app_config 表读 API Key（与清洗共享同一个 key）。
 * Neo4j 连接参数有默认值，也可从 app_config 读。
 */
@Service
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);

    private volatile boolean isRunning = false;
    private volatile GraphBuilder.GraphStats lastStats = null;
    private volatile String currentJobId = null;
    private volatile int processedChunks = 0;
    private volatile int totalChunks = 0;
    private volatile int totalEntities = 0;
    private volatile int totalRelations = 0;
    private volatile int filteredByModeration = 0;

    @Async
    public void startBuild(String jobId, String jsonlPath) {
        currentJobId = jobId;
        isRunning = true;
        processedChunks = 0;
        totalChunks = 0;
        totalEntities = 0;
        totalRelations = 0;
        filteredByModeration = 0;

        log.info("图谱构建启动: jobId={}, input={}", jobId, jsonlPath);

        try {
            // 从 app_config 表读 API Key
            String apiKey = getApiKeyFromConfig();
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = System.getenv("DASHSCOPE_API_KEY");
            }

            // Neo4j 配置
            String neo4jUri = getConfig("neo4j_uri", "bolt://localhost:7687");
            String neo4jUser = getConfig("neo4j_user", "neo4j");
            String neo4jPass = getConfig("neo4j_password", "knowly_dev");

            GraphBuilder builder = new GraphBuilder(apiKey, neo4jUri, neo4jUser, neo4jPass);
            lastStats = builder.build(Path.of(jsonlPath), this::onProgress);

        } catch (Exception e) {
            log.error("图谱构建异常: {}", e.getMessage(), e);
        } finally {
            isRunning = false;
        }
    }

    /**
     * GraphBuilder 的进度回调——更新自身 volatile 字段供前端轮询。
     */
    private void onProgress(int processed, int total, int entities, int relations, int filtered) {
        this.processedChunks = processed;
        this.totalChunks = total;
        this.totalEntities = entities;
        this.totalRelations = relations;
        this.filteredByModeration = filtered;
    }

    public boolean isRunning() { return isRunning; }

    public Map<String, Object> getStatus() {
        if (isRunning) {
            return Map.of(
                    "jobId", currentJobId != null ? currentJobId : "",
                    "status", "RUNNING",
                    "processedChunks", processedChunks,
                    "totalChunks", totalChunks,
                    "entities", totalEntities,
                    "relations", totalRelations,
                    "filteredByModeration", filteredByModeration
            );
        }
        if (lastStats != null) {
            return Map.of(
                    "jobId", currentJobId != null ? currentJobId : "",
                    "status", "SUCCESS",
                    "totalChunks", lastStats.totalChunks(),
                    "totalEntities", lastStats.totalEntities(),
                    "totalRelations", lastStats.totalRelations(),
                    "failedChunks", lastStats.failedChunks(),
                    "filteredByModeration", lastStats.filteredByModeration()
            );
        }
        return null;
    }

    public void cancel() {
        // [待完善] 实际的取消逻辑
        log.info("图谱构建取消请求");
    }

    private String getApiKeyFromConfig() {
        String knowlyHome = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly");
        String dbPath = java.nio.file.Path.of(knowlyHome, "knowly.db").toString();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM app_config WHERE key = ?");
            ps.setString(1, "dashscope_api_key");
            java.sql.ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (Exception ignored) {}
        return null;
    }

    private String getConfig(String key, String defaultValue) {
        String knowlyHome = System.getenv().getOrDefault("KNOWLY_HOME",
                System.getProperty("user.home") + "/.knowly");
        String dbPath = java.nio.file.Path.of(knowlyHome, "knowly.db").toString();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM app_config WHERE key = ?");
            ps.setString(1, key);
            java.sql.ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (Exception ignored) {}
        return defaultValue;
    }
}
