package com.knowly.core.model;

import java.util.Map;

/**
 * 向量化后的块（向量化层产出）。
 *
 * <p>仅当配置了向量库 sink 时才存在。embedding 与模型绑定，换模型全量重算，
 * 因此 EmbeddedChunk 不是核心资产，可随时重算。
 *
 * @param id        与 TextChunk.id 一致
 * @param text      文本（冗余存向量库，便于检索展示）
 * @param embedding 向量（维度 1024，text-embedding-v3）
 * @param documentId 所属文档 ID
 * @param metadata  完整元数据（扁平 Map，便于存 JSONB/JSON）
 */
public record EmbeddedChunk(
        String id,
        String text,
        float[] embedding,
        String documentId,
        Map<String, Object> metadata
) {}
