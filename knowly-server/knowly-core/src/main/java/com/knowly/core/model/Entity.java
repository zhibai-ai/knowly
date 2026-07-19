package com.knowly.core.model;

import java.util.List;

/**
 * 实体（图谱层，v0.2）。v0.1 仅定义模型，不实现抽取。
 *
 * @param id            实体 ID
 * @param name          实体名（规范化后）
 * @param type          类型（方剂/穴位/概念/人物...）
 * @param normalizedKey 规范化键（消歧用）
 * @param aliases       别名列表
 */
public record Entity(
        String id,
        String name,
        String type,
        String normalizedKey,
        List<String> aliases
) {}
