package com.knowly.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowly.core.model.Entity;
import com.knowly.core.model.Relation;
import com.knowly.core.model.TextChunk;
import com.knowly.graph.extractor.LlmEntityExtractor;
import com.knowly.graph.extractor.LlmRelationExtractor;
import com.knowly.graph.llm.DashScopeLlmProvider;
import com.knowly.graph.store.Neo4jGraphStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图谱构建引擎——独立的步骤，输入是清洗产出的 JSONL。
 *
 * <p>流程：读 JSONL → 逐 chunk 抽实体 → 抽关系 → 消歧 → 写 Neo4j
 * <p>与清洗流水线完全解耦——改 prompt/类型不用重跑清洗。
 */
public class GraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmEntityExtractor entityExtractor;
    private final LlmRelationExtractor relationExtractor;
    private final Neo4jGraphStore graphStore;

    public GraphBuilder(String dashscopeApiKey, String neo4jUri, String neo4jUser, String neo4jPassword) {
        this(dashscopeApiKey, neo4jUri, neo4jUser, neo4jPassword, null, null);
    }

    /**
     * 自定义实体/关系类型的图谱构建。
     *
     * @param entityTypes   实体类型（null 用默认通用类型；领域专用类型如易卦的「卦象/象征物」通过此注入）
     * @param relationTypes 关系类型（null 用默认通用类型）
     */
    public GraphBuilder(String dashscopeApiKey, String neo4jUri, String neo4jUser, String neo4jPassword,
                        List<String> entityTypes, List<String> relationTypes) {
        var llm = new DashScopeLlmProvider(dashscopeApiKey);
        this.entityExtractor = entityTypes != null
                ? new LlmEntityExtractor(llm, entityTypes) : new LlmEntityExtractor(llm);
        this.relationExtractor = relationTypes != null
                ? new LlmRelationExtractor(llm, relationTypes) : new LlmRelationExtractor(llm);
        this.graphStore = new Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPassword);
    }

    /**
     * 从 JSONL 构建知识图谱。
     *
     * @param jsonlPath 清洗产出的 chunks.jsonl 路径
     * @return 构建统计
     */
    public GraphStats build(Path jsonlPath) {
        log.info("图谱构建开始: {}", jsonlPath);

        int totalChunks = 0;
        int totalEntities = 0;
        int totalRelations = 0;
        int failedChunks = 0;

        try {
            List<String> lines = Files.readAllLines(jsonlPath);
            totalChunks = lines.size();

            // 全局实体去重（跨 chunk 合并）
            Map<String, Entity> allEntities = new java.util.HashMap<>();
            List<Relation> allRelations = new ArrayList<>();

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                try {
                    // 解析 JSONL 行为 TextChunk
                    Map<String, Object> obj = MAPPER.readValue(line, Map.class);
                    String chunkId = (String) obj.get("id");
                    String documentId = (String) obj.get("documentId");
                    String sourcePath = (String) obj.get("sourcePath");
                    String text = (String) obj.get("text");
                    int ordinal = (Integer) obj.get("ordinal");

                    TextChunk chunk = new TextChunk(chunkId, documentId, sourcePath, text, ordinal, null);

                    // 抽取实体
                    List<Entity> entities = entityExtractor.extract(chunk);
                    for (Entity e : entities) {
                        allEntities.merge(e.normalizedKey(), e, (existing, newer) -> {
                            List<String> aliases = new ArrayList<>(existing.aliases());
                            for (String a : newer.aliases()) {
                                if (!aliases.contains(a)) aliases.add(a);
                            }
                            return new Entity(existing.id(), existing.name(), existing.type(),
                                    existing.normalizedKey(), aliases);
                        });
                    }

                    // 抽取关系（用本 chunk 抽取的实体）
                    List<Relation> relations = relationExtractor.extract(chunk, entities);
                    allRelations.addAll(relations);

                    totalEntities = allEntities.size();
                    totalRelations = allRelations.size();

                    if ((i + 1) % 50 == 0) {
                        log.info("图谱构建进度: {}/{} chunks, {} 实体, {} 关系",
                                i + 1, totalChunks, totalEntities, totalRelations);
                    }

                } catch (Exception e) {
                    log.warn("Chunk {} 图谱抽取失败: {}", i, e.getMessage());
                    failedChunks++;
                }
            }

            // 批量写入 Neo4j
            log.info("写入 Neo4j: {} 实体, {} 关系", totalEntities, totalRelations);
            graphStore.upsertEntities(new ArrayList<>(allEntities.values()));
            graphStore.upsertRelations(allRelations);

        } catch (Exception e) {
            log.error("图谱构建失败: {}", e.getMessage(), e);
        } finally {
            graphStore.close();
        }

        GraphStats stats = new GraphStats(totalChunks, totalEntities, totalRelations, failedChunks);
        log.info("图谱构建完成: {}", stats);
        return stats;
    }

    /** 图谱构建统计 */
    public record GraphStats(int totalChunks, int totalEntities, int totalRelations, int failedChunks) {}
}
