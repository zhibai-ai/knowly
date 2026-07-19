package com.knowly.embed.sink.pgvector;

import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.SinkException;
import com.knowly.core.model.EmbeddedChunk;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.VectorSearchable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL + PgVector 向量库 Sink（可选替代）。
 *
 * <p>通过 JDBC 写入向量数据。幂等：按 chunk id 主键 upsert，中断重跑无重复。
 *
 * <p>同时实现 VectorSearchable（仅供自测），知了不做查询服务。
 *
 * <p>安全：一律用 PreparedStatement 参数化，防 SQL 注入。
 */
public class PgVectorSink implements ChunkSink, VectorSearchable {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSink.class);

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;
    private final int dimension;
    private Connection connection;

    /**
     * 创建 PgVector Sink。
     *
     * @param host      PostgreSQL 主机
     * @param port      端口（知了默认 5433）
     * @param database  数据库名
     * @param username  用户名
     * @param password  密码
     * @param tableName 表名
     * @param dimension 向量维度（1024）
     */
    public PgVectorSink(String host, int port, String database,
                        String username, String password,
                        String tableName, int dimension) {
        this.jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + database;
        this.username = username;
        this.password = password;
        this.tableName = tableName;
        this.dimension = dimension;
        connect();
        ensureTable();
        log.info("PgVectorSink 初始化: host={}:{}, db={}, table={}, dim={}",
                host, port, database, tableName, dimension);
    }

    @Override
    public String type() { return "pgvector"; }

    @Override
    public boolean requiresEmbedding() { return true; }

    @Override
    public void write(List<TextChunk> chunks) {
        // PgVector 不存原始 TextChunk，空实现
    }

    @Override
    public void writeEmbedded(List<EmbeddedChunk> chunks) {
        if (chunks.isEmpty()) return;

        // 幂等 upsert（按 id 主键覆盖写）
        String sql = "INSERT INTO " + tableName + " "
                + "(id, document_id, text, embedding, section_title, section_level, start_page, source_path, metadata) "
                + "VALUES (?, ?, ?, ?::vector, ?, ?, ?, ?, ?::jsonb) "
                + "ON CONFLICT (id) DO UPDATE SET "
                + "text = EXCLUDED.text, embedding = EXCLUDED.embedding, "
                + "section_title = EXCLUDED.section_title, metadata = EXCLUDED.metadata";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int count = 0;
            for (EmbeddedChunk chunk : chunks) {
                ps.setString(1, chunk.id());
                ps.setString(2, chunk.documentId());
                ps.setString(3, chunk.text());
                ps.setString(4, toPgVectorString(chunk.embedding()));
                // metadata
                ps.setString(5, getMeta(chunk, "sectionTitle"));
                setNullableInt(ps, 6, getMetaInt(chunk, "sectionLevel"));
                setNullableInt(ps, 7, getMetaInt(chunk, "startPage"));
                ps.setString(8, getMeta(chunk, "sourcePath"));
                ps.setString(9, toPgJsonb(chunk.metadata()));
                ps.addBatch();
                count++;
                if (count % 100 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
            log.debug("PgVector upsert: {} chunks", chunks.size());
        } catch (SQLException e) {
            throw new SinkException(ErrorCode.SINK_001, "PgVector 写入失败",
                    "cause=" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByDocument(String documentId) {
        String sql = "DELETE FROM " + tableName + " WHERE document_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, documentId);
            int deleted = ps.executeUpdate();
            log.debug("PgVector 按文档删除: {} ({} rows)", documentId, deleted);
        } catch (SQLException e) {
            log.warn("PgVector 删除失败: docId={}, cause={}", documentId, e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    // VectorSearchable（仅供自测）
    // ────────────────────────────────────────────────────────────

    @Override
    public List<com.knowly.core.model.EmbeddedChunk> search(float[] queryVector, int topK) {
        String sql = "SELECT id, document_id, text, embedding::text, metadata::text "
                + "FROM " + tableName + " "
                + "ORDER BY embedding <=> ?::vector LIMIT ?";

        List<EmbeddedChunk> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, toPgVectorString(queryVector));
            ps.setInt(2, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new EmbeddedChunk(
                            rs.getString("id"),
                            rs.getString("text"),
                            null,  // 不返回 embedding（省带宽）
                            rs.getString("document_id"),
                            null   // metadata 反序列化简化
                    ));
                }
            }
        } catch (SQLException e) {
            throw new SinkException(ErrorCode.SINK_001, "PgVector 检索失败",
                    "cause=" + e.getMessage(), e);
        }
        return results;
    }

    // ────────────────────────────────────────────────────────────
    // 内部方法
    // ────────────────────────────────────────────────────────────

    private void connect() {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl, username, password);
            this.connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new SinkException(ErrorCode.SINK_002, "PostgreSQL 连接失败",
                    "url=" + jdbcUrl + ", cause=" + e.getMessage(), e);
        }
    }

    /** 确保表和索引存在 */
    private void ensureTable() {
        try (Statement st = connection.createStatement()) {
            // PgVector 扩展
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            // 建表
            st.execute("CREATE TABLE IF NOT EXISTS " + tableName + " ("
                    + "id VARCHAR(128) PRIMARY KEY, "
                    + "document_id VARCHAR(64) NOT NULL, "
                    + "text TEXT NOT NULL, "
                    + "embedding vector(" + dimension + "), "
                    + "section_title VARCHAR(512), "
                    + "section_level SMALLINT, "
                    + "start_page INT, "
                    + "source_path TEXT, "
                    + "created_at TIMESTAMPTZ DEFAULT NOW(), "
                    + "metadata JSONB DEFAULT '{}'::jsonb"
                    + ")");
            // 索引
            st.execute("CREATE INDEX IF NOT EXISTS idx_" + tableName + "_doc "
                    + "ON " + tableName + "(document_id)");
        } catch (SQLException e) {
            throw new SinkException(ErrorCode.SINK_002, "PgVector 表初始化失败",
                    "table=" + tableName + ", cause=" + e.getMessage(), e);
        }
    }

    /** float[] → PgVector 格式字符串 "[0.1,0.2,...]" */
    private static String toPgVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String toPgJsonb(java.util.Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return "{}";
        // 简化 JSON 序列化（生产用 Jackson）
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : metadata.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object v = entry.getValue();
            if (v instanceof String s) {
                sb.append("\"").append(s.replace("\"", "\\\"")).append("\"");
            } else {
                sb.append(v);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String getMeta(EmbeddedChunk chunk, String key) {
        if (chunk.metadata() == null) return null;
        Object v = chunk.metadata().get(key);
        return v != null ? v.toString() : null;
    }

    private static int getMetaInt(EmbeddedChunk chunk, String key) {
        if (chunk.metadata() == null) return 0;
        Object v = chunk.metadata().get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static void setNullableInt(PreparedStatement ps, int idx, int val) throws SQLException {
        if (val == 0) {
            ps.setNull(idx, java.sql.Types.INTEGER);
        } else {
            ps.setInt(idx, val);
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.debug("PgVector 连接已关闭");
            } catch (SQLException e) {
                log.warn("PgVector 连接关闭失败: {}", e.getMessage());
            }
        }
    }
}
