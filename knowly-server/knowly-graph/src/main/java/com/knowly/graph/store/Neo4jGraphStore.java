package com.knowly.graph.store;

import com.knowly.core.model.Entity;
import com.knowly.core.model.Relation;
import com.knowly.core.spi.GraphStore;
import java.util.List;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Neo4j 图谱存储实现。
 *
 * <p>用 Cypher MERGE 语句幂等写入实体节点和关系边。
 * 实体节点带 name/type/normalizedKey/aliases 属性。
 * 关系边带 type/confidence/source 属性。
 */
public class Neo4jGraphStore implements GraphStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphStore.class);

    private final Driver driver;

    public Neo4jGraphStore(String uri, String user, String password) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
        // 验证连接
        try (Session session = driver.session()) {
            session.run("RETURN 1").consume();
        }
        log.info("Neo4jGraphStore 连接成功: {}", uri);
    }

    @Override
    public void upsertEntities(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) return;
        try (Session session = driver.session()) {
            for (Entity entity : entities) {
                session.run("""
                    MERGE (e:Entity {id: $id})
                    SET e.name = $name,
                        e.type = $type,
                        e.normalizedKey = $normalizedKey,
                        e.aliases = $aliases
                    """,
                    org.neo4j.driver.Values.parameters(
                            "id", entity.id() != null ? entity.id() : "",
                            "name", entity.name() != null ? entity.name() : "",
                            "type", entity.type() != null ? entity.type() : "",
                            "normalizedKey", entity.normalizedKey() != null ? entity.normalizedKey() : "",
                            "aliases", entity.aliases() != null ? entity.aliases() : List.of()
                    ));
            }
            log.debug("写入实体: {} 个", entities.size());
        } catch (Exception e) {
            log.warn("Neo4j 写入实体失败: {}", e.getMessage());
        }
    }

    @Override
    public void upsertRelations(List<Relation> relations) {
        if (relations == null || relations.isEmpty()) return;
        try (Session session = driver.session()) {
            for (Relation rel : relations) {
                session.run("""
                    MATCH (source:Entity {id: $sourceId}), (target:Entity {id: $targetId})
                    MERGE (source)-[r:RELATES {id: $relId}]->(target)
                    SET r.type = $type,
                        r.confidence = $confidence,
                        r.source = $source
                    """,
                    org.neo4j.driver.Values.parameters(
                            "sourceId", rel.sourceEntityId(),
                            "targetId", rel.targetEntityId(),
                            "relId", rel.id() != null ? rel.id() : "",
                            "type", rel.type() != null ? rel.type() : "",
                            "confidence", rel.confidence(),
                            "source", rel.source() != null ? rel.source() : ""
                    ));
            }
            log.debug("写入关系: {} 个", relations.size());
        } catch (Exception e) {
            log.warn("Neo4j 写入关系失败: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (driver != null) {
            driver.close();
            log.info("Neo4j 连接已关闭");
        }
    }
}
