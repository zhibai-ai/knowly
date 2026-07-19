package com.knowly.core.spi;

import com.knowly.common.enums.ProcessStage;
import com.knowly.common.enums.StageStatus;
import java.util.Optional;

/**
 * 状态存储 SPI（断点续跑用）。
 *
 * <p>记录每个文件在每个清洗任务中每个阶段的处理状态。
 * 中断后重启，按此状态从断点恢复，已完成跳过。
 *
 * <p>默认实现 SqliteStateRepository（SQLite 文件型）。
 */
public interface StateRepository {

    /**
     * 记录某文件在某清洗任务中某阶段的状态。
     *
     * @param jobId       清洗任务 ID
     * @param filePath    文件路径
     * @param contentHash 内容哈希
     * @param documentId  文档 ID（由 hash 派生）
     * @param stage       处理阶段
     * @param status      阶段状态
     */
    void markStage(String jobId, String filePath, String contentHash,
                   String documentId, ProcessStage stage, StageStatus status);

    /**
     * 查询某文件在某任务某阶段的状态。
     */
    StageStatus getStageStatus(String jobId, String filePath, ProcessStage stage);

    /**
     * 获取某文件在某任务中最后完成的阶段（断点续跑起点）。
     *
     * @return 最后完成的阶段；若未开始则返回 null
     */
    ProcessStage getLastCompletedStage(String jobId, String filePath);

    /**
     * 检测某任务是否有未完成的文件（启动时判断是否需要提示续跑）。
     *
     * @param jobId 清洗任务 ID
     * @return true 如果有未完成的文件
     */
    boolean hasUnfinishedFiles(String jobId);

    /**
     * 清理某任务的所有状态（用户取消时调）。
     *
     * @param jobId 清洗任务 ID
     */
    void cleanByJob(String jobId);
}
