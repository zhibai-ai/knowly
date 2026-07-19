package com.knowly.core.state;

import com.knowly.common.enums.ProcessStage;
import com.knowly.common.enums.StageStatus;
import com.knowly.core.spi.StateRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link StateRepository} 的默认实现——基于 SQLite 文件型存储，用于断点续跑。
 *
 * <p>每个清洗任务的所有阶段状态写入单个 SQLite 文件（{@code job_files} 表）。
 * 中断重启后，按记录的状态跳过已完成阶段、续跑未完成部分。
 *
 * <p>表结构：
 * <pre>
 * CREATE TABLE IF NOT EXISTS job_files (
 *     job_id       TEXT,
 *     file_path    TEXT,
 *     content_hash TEXT,
 *     document_id  TEXT,
 *     stage        TEXT,
 *     status       TEXT,
 *     updated_at   TEXT,
 *     PRIMARY KEY (job_id, file_path, stage)
 * );
 * </pre>
 *
 * <p>线程模型：每次操作独立获取 {@link Connection}（SQLite 单写者模型），并用
 * try-with-resources 保证释放。对断点续跑的轻量写入场景足够。
 */
public class SqliteStateRepository implements StateRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteStateRepository.class);

    /** 流水线阶段顺序（断点续跑查找最后完成阶段用） */
    private static final ProcessStage[] STAGE_ORDER = {
            ProcessStage.INGEST,
            ProcessStage.CLEAN,
            ProcessStage.EMBED,
            ProcessStage.SINK
    };

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS job_files (
                job_id       TEXT,
                file_path    TEXT,
                content_hash TEXT,
                document_id  TEXT,
                stage        TEXT,
                status       TEXT,
                updated_at   TEXT,
                PRIMARY KEY (job_id, file_path, stage)
            )
            """;

    private static final String UPSERT_SQL = """
            INSERT OR REPLACE INTO job_files
                (job_id, file_path, content_hash, document_id, stage, status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_STATUS_SQL = """
            SELECT status FROM job_files
            WHERE job_id = ? AND file_path = ? AND stage = ?
            """;

    private static final String COUNT_UNFINISHED_SQL = """
            SELECT COUNT(*) FROM job_files
            WHERE job_id = ? AND status IN ('IN_PROGRESS', 'FAILED', 'PENDING')
            """;

    private static final String DELETE_BY_JOB_SQL = "DELETE FROM job_files WHERE job_id = ?";

    private final Path dbPath;
    private final String jdbcUrl;

    /**
     * 构造并初始化状态库。
     *
     * <p>会自动创建 dbPath 的父目录，并执行建表语句（IF NOT EXISTS，幂等）。
     *
     * @param dbPath SQLite 数据库文件路径
     */
    public SqliteStateRepository(Path dbPath) {
        this.dbPath = dbPath;
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toString();
        init();
    }

    /** 建表（幂等），确保父目录存在 */
    private void init() {
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            log.warn("创建状态库父目录失败: {}", dbPath.getParent(), e);
        }
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE_SQL);
            log.debug("状态库已就绪: {}", dbPath);
        } catch (SQLException e) {
            log.error("初始化状态库失败: {}", dbPath, e);
            throw new IllegalStateException("初始化状态库失败: " + dbPath, e);
        }
    }

    @Override
    public void markStage(String jobId, String filePath, String contentHash,
                          String documentId, ProcessStage stage, StageStatus status) {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
            ps.setString(1, jobId);
            ps.setString(2, filePath);
            ps.setString(3, contentHash);
            ps.setString(4, documentId);
            ps.setString(5, stage.name());
            ps.setString(6, status.name());
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
            log.debug("记录状态: jobId={}, file={}, stage={}, status={}",
                    jobId, filePath, stage, status);
        } catch (SQLException e) {
            log.error("写入状态失败: jobId={}, file={}, stage={}, status={}",
                    jobId, filePath, stage, status, e);
        }
    }

    @Override
    public StageStatus getStageStatus(String jobId, String filePath, ProcessStage stage) {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_STATUS_SQL)) {
            ps.setString(1, jobId);
            ps.setString(2, filePath);
            ps.setString(3, stage.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String status = rs.getString("status");
                    return status != null ? StageStatus.valueOf(status) : null;
                }
            }
        } catch (SQLException e) {
            log.error("查询阶段状态失败: jobId={}, file={}, stage={}", jobId, filePath, stage, e);
        } catch (IllegalArgumentException e) {
            log.warn("未知状态值: jobId={}, file={}, stage={}", jobId, filePath, stage, e);
        }
        return null;
    }

    /**
     * 按 INGEST → CLEAN → EMBED → SINK 顺序，返回最后一个状态为 SUCCESS 的阶段。
     *
     * @return 最后完成的阶段；若未完成任何阶段则返回 null
     */
    @Override
    public ProcessStage getLastCompletedStage(String jobId, String filePath) {
        ProcessStage lastCompleted = null;
        for (ProcessStage stage : STAGE_ORDER) {
            StageStatus status = getStageStatus(jobId, filePath, stage);
            if (status == StageStatus.SUCCESS) {
                lastCompleted = stage;
            } else {
                // 阶段顺序执行：遇到非 SUCCESS 即终止（后续阶段不会先完成）
                break;
            }
        }
        return lastCompleted;
    }

    @Override
    public boolean hasUnfinishedFiles(String jobId) {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_UNFINISHED_SQL)) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("查询未完成文件失败: jobId={}", jobId, e);
            return false;
        }
    }

    @Override
    public void cleanByJob(String jobId) {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_BY_JOB_SQL)) {
            ps.setString(1, jobId);
            int deleted = ps.executeUpdate();
            log.info("清理任务状态: jobId={}, 删除记录数={}", jobId, deleted);
        } catch (SQLException e) {
            log.error("清理任务状态失败: jobId={}", jobId, e);
        }
    }

    /** 获取一个新的 SQLite 连接 */
    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /** 暴露数据库路径（调试 / 测试用） */
    public Path getDbPath() {
        return dbPath;
    }
}
