package com.knowly.core.model;

import com.knowly.common.enums.BlockType;
import java.util.List;

/**
 * 文本块（清洗层产出）——知了最核心的资产。
 *
 * <p>一个知识单元。所有产出（Markdown/JSONL/向量库）都由它派生。
 * id 格式：{documentId}#{ordinal}，文件内稳定，保证 sink 幂等。
 *
 * @param id          chunk id = documentId + "#" + ordinal
 * @param documentId  所属文档 ID（追溯源）
 * @param sourcePath  源文件路径（冗余，方便追溯）
 * @param text        块文本内容
 * @param ordinal     在文档中的顺序（从 0 开始）
 * @param metadata    块元数据
 */
public record TextChunk(
        String id,
        String documentId,
        String sourcePath,
        String text,
        int ordinal,
        ChunkMetadata metadata
) {}
