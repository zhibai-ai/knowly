package com.knowly.common.enums;

/**
 * 文档解析状态。
 *
 * <p>描述文档整体解析的最终结果：
 *
 * <ul>
 *   <li>{@link #SUCCESS} - 成功：文档完整解析，无异常</li>
 *   <li>{@link #PARTIAL} - 部分成功：文档部分内容解析成功，部分失败或被跳过</li>
 *   <li>{@link #FAILED} - 失败：文档解析彻底失败</li>
 * </ul>
 */
public enum ParseStatus {
    SUCCESS("成功"),
    PARTIAL("部分成功"),
    FAILED("失败");

    private final String description;

    ParseStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
