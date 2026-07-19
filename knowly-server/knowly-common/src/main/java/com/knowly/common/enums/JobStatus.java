package com.knowly.common.enums;

/**
 * 清洗任务状态（clean_jobs 表的 status 字段）。
 *
 * <p>描述清洗任务在其生命周期中的整体状态：
 *
 * <ul>
 *   <li>{@link #PENDING} - 待处理：任务已创建，尚未调度执行</li>
 *   <li>{@link #RUNNING} - 运行中：任务已被调度，正在执行</li>
 *   <li>{@link #SUCCESS} - 成功：任务执行成功</li>
 *   <li>{@link #FAILED} - 失败：任务执行失败</li>
 *   <li>{@link #CANCELED} - 已取消：任务被手动或系统取消</li>
 * </ul>
 */
public enum JobStatus {
    PENDING("待处理"),
    RUNNING("运行中"),
    SUCCESS("成功"),
    FAILED("失败"),
    CANCELED("已取消");

    private final String description;

    JobStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
