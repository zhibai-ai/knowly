package com.knowly.core.spi;

import com.knowly.common.exception.ParseException;
import java.nio.file.Path;

/**
 * 版面分析器 SPI。识别双栏/表格/页眉页脚/标题区域。
 * 默认实现 RuleBasedLayoutAnalyzer（轻量规则版）。
 */
public interface LayoutAnalyzer {
    /**
     * 版面分析：识别版面区域，返回带版面信息的分析结果。
     * @param filePath 文件路径
     * @param rawText  初步提取的文本
     * @return 带版面区域信息的分析结果
     */
    AnalyzedLayout analyze(Path filePath, String rawText) throws ParseException;

    /** 版面分析结果（简化版，v0.1 用 record） */
    record AnalyzedLayout(String text, boolean isTwoColumn, java.util.List<LayoutRegion> regions) {}

    /** 版面区域 */
    record LayoutRegion(String type, double x, double y, double width, double height, String text) {}
}
