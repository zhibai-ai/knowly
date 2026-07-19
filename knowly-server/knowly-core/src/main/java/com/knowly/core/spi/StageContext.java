package com.knowly.core.spi;

import java.nio.file.Path;
import java.util.Map;

/**
 * 阶段上下文。携带执行阶段所需的配置、状态存储、错误收集器等。
 *
 * <p>由 PipelineEngine 创建并传入每个 Stage。
 */
public interface StageContext {

    /** 输入目录 */
    Path inputDir();

    /** 输出目录 */
    Path outputDir();

    /** 该阶段的配置（从 YAML 解析） */
    Map<String, Object> config();

    /** 当前清洗任务 ID（关联 job_files 表） */
    String jobId();

    /** 状态存储（断点续跑用） */
    StateRepository state();

    /** 错误收集器（单文件失败记录到此，不中断流水线） */
    com.knowly.core.error.ErrorCollector errors();
}
