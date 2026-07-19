package com.knowly.core.spi;

/**
 * 流水线阶段 SPI。每个阶段职责单一、可独立测试、可跳过。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface PipelineStage<I, O> {

    /** 阶段名称 */
    String name();

    /**
     * 执行本阶段处理。
     *
     * @param context 阶段上下文（配置/状态/错误收集）
     * @param input   输入数据
     * @return 阶段结果（成功产出 或 跳过）
     */
    StageResult<O> execute(StageContext context, I input);
}
