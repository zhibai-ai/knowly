package com.knowly.core.model;

/**
 * 关系（图谱层，v0.2）。v0.1 仅定义模型，不实现抽取。
 *
 * @param id             关系 ID
 * @param sourceEntityId 源实体 ID
 * @param targetEntityId 目标实体 ID
 * @param type           关系类型（组成/主治/相关...）
 * @param source         出自哪个文档/块
 * @param confidence     置信度（0-1）
 */
public record Relation(
        String id,
        String sourceEntityId,
        String targetEntityId,
        String type,
        String source,
        double confidence
) {}
