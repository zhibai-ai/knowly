package com.knowly.core.error;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 错误收集器。每个 Stage 把失败项收集到此，流水线不中断。
 *
 * <p>线程安全（CopyOnWriteArrayList），支持并发处理下的错误收集。
 * 最终产出 error-report.json。
 */
public class ErrorCollector {

    private final List<FailureRecord> failures = new CopyOnWriteArrayList<>();

    /**
     * 记录一个失败。
     *
     * @param filePath   文件路径
     * @param documentId 文档 ID（可 null）
     * @param stage      失败阶段（INGEST/CLEAN/EMBED/SINK）
     * @param errorCode  错误码
     * @param message    错误信息
     */
    public void record(String filePath, String documentId, String stage,
                       String errorCode, String message) {
        failures.add(new FailureRecord(filePath, documentId, stage, errorCode, message));
    }

    /** 所有失败记录 */
    public List<FailureRecord> getFailures() {
        return List.copyOf(failures);
    }

    /** 失败文件数（按路径去重——同一文件多阶段失败只计一次，与 succeeded 同口径。
     *  曾直接返回记录条数，致 processing-report 出现 62+14=76>63 的算术矛盾）。 */
    public int failureCount() {
        return (int) failures.stream().map(FailureRecord::filePath).distinct().count();
    }

    /** 是否有失败 */
    public boolean hasFailures() {
        return !failures.isEmpty();
    }
}
