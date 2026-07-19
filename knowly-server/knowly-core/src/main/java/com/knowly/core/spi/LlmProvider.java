package com.knowly.core.spi;

/**
 * LLM 提供者 SPI（v0.1 预留，v0.2 图谱层实体/关系抽取用）。
 */
public interface LlmProvider {
    /**
     * 单轮对话。
     * @param prompt 提示词
     * @return LLM 响应文本
     */
    String chat(String prompt);
}
