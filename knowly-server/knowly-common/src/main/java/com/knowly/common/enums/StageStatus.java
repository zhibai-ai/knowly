package com.knowly.common.enums;

/**
 * 某文件在某阶段的处理状态（断点续跑用）。
 *
 * <p>用于记录单个文件在流水线某个 {@link ProcessStage} 的执行结果，便于失败重试与断点续跑。
 *
 * <ul>
 *   <li>{@link #PENDING} - 待处理：尚未进入该阶段</li>
 *   <li>{@link #IN_PROGRESS} - 处理中：正在执行该阶段</li>
 *   <li>{@link #SUCCESS} - 成功：该阶段已成功完成</li>
 *   <li>{@link #FAILED} - 失败：该阶段执行失败，需重试</li>
 * </ul>
 */
public enum StageStatus {
    PENDING("待处理"),
    IN_PROGRESS("处理中"),
    SUCCESS("成功"),
    FAILED("失败");

    private final String description;

    StageStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
