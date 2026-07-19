package com.knowly.core.model;

/**
 * 文档中的图片信息。
 *
 * @param page      所在页码（从1开始）
 * @param filePath  提取后的图片文件相对路径（相对于输出目录）
 * @param altText   图片描述（如卦名、图表标题）
 */
public record DocumentImage(
        int page,
        String filePath,
        String altText
) {}
