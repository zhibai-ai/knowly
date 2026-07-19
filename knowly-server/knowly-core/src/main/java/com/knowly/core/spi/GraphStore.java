package com.knowly.core.spi;

import com.knowly.core.model.Entity;
import com.knowly.core.model.Relation;
import java.util.List;

/**
 * 图谱存储 SPI（图谱层，v0.2）。默认实现 Neo4jGraphStore。v0.1 仅定义接口。
 */
public interface GraphStore {
    /** 写入/更新实体（幂等，按 entity id） */
    void upsertEntities(List<Entity> entities);
    /** 写入/更新关系（幂等，按 relation id） */
    void upsertRelations(List<Relation> relations);
}
