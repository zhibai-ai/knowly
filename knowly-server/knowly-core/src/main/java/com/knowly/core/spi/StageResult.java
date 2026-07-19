package com.knowly.core.spi;

/**
 * 阶段执行结果。
 *
 * @param <O> 输出类型
 */
public interface StageResult<O> {

    /** 结果状态 */
    enum Status {
        SUCCESS,  // 成功，有产出
        SKIPPED,  // 跳过（如已处理、去重命中）
        FAILED    // 失败（已记录到 ErrorCollector，流水线继续）
    }

    /** 结果状态 */
    Status status();

    /** 产出（SUCCESS 时有值，SKIPPED/FAILED 时为 null） */
    O data();

    /** 创建成功结果 */
    static <O> StageResult<O> success(O data) {
        return new StageResult<>() {
            @Override public Status status() { return Status.SUCCESS; }
            @Override public O data() { return data; }
        };
    }

    /** 创建跳过结果 */
    static <O> StageResult<O> skipped() {
        return new StageResult<>() {
            @Override public Status status() { return Status.SKIPPED; }
            @Override public O data() { return null; }
        };
    }

    /** 创建失败结果 */
    static <O> StageResult<O> failed() {
        return new StageResult<>() {
            @Override public Status status() { return Status.FAILED; }
            @Override public O data() { return null; }
        };
    }
}
