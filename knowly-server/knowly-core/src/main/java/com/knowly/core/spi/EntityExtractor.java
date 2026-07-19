package com.knowly.core.spi;

import com.knowly.core.model.Entity;
import com.knowly.core.model.Relation;
import com.knowly.core.model.TextChunk;
import java.util.List;

/**
 * 实体抽取器 SPI（图谱层，v0.2）。v0.1 仅定义接口，不实现。
 */
public interface EntityExtractor {
    /**
     * 从文本块抽取实体。
     * @param chunk 文本块
     * @return 实体列表
     */
    List<Entity> extract(TextChunk chunk);
}
