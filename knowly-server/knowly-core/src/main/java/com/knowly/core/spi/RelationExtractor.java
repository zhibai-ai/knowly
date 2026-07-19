package com.knowly.core.spi;

import com.knowly.core.model.Entity;
import com.knowly.core.model.Relation;
import com.knowly.core.model.TextChunk;
import java.util.List;

/**
 * 关系抽取器 SPI（图谱层，v0.2）。v0.1 仅定义接口，不实现。
 */
public interface RelationExtractor {
    /**
     * 从文本块抽取实体间关系。
     * @param chunk    文本块
     * @param entities 已抽取的实体列表（关系指向这些实体）
     * @return 关系列表
     */
    List<Relation> extract(TextChunk chunk, List<Entity> entities);
}
