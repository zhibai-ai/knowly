package com.knowly.core.model;

import com.knowly.common.enums.BlockType;
import java.util.List;

/**
 * 块元数据。
 *
 * @param sectionTitle  所属章节标题
 * @param sectionLevel  标题层级（1=H1, 2=H2...）
 * @param startPage     起始页码（PDF）
 * @param charCount     字符数
 * @param blockType     块类型：HEADING/PARAGRAPH/LIST/TABLE/QUOTE/CODE
 * @param tags          自动打的标签
 * @param origElementIds 可溯源：本 chunk 由哪些原始元素合并而来（v0.1 预留，v0.2 启用）
 */
public record ChunkMetadata(
        String sectionTitle,
        int sectionLevel,
        int startPage,
        int charCount,
        BlockType blockType,
        List<String> tags,
        List<String> origElementIds
) {}
