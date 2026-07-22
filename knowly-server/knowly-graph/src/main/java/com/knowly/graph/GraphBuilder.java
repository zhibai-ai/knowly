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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图谱构建引擎——独立的步骤，输入是清洗产出的 JSONL。
 *
 * <p>流程：读 JSONL → 逐 chunk 抽实体 → 抽关系 → 消歧 → 流式写 Neo4j
 * <p>与清洗流水线完全解耦——改 prompt/类型不用重跑清洗。
 *
 * <p>进度回传：通过 {@link ProgressCallback} 在每 FLUSH_BATCH 个 chunk 后回调一次，
 * GraphService 可借此更新自身状态供前端轮询。
 *
 * <p>流式写入：每 FLUSH_BATCH 个 chunk 把累积的实体/关系刷入 Neo4j（MERGE 幂等），
 * 中途崩溃已写入部分仍保留。
 */
public class GraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 每处理多少个 chunk 触发一次：进度回调 + 流式写入 Neo4j */
    private static final int FLUSH_BATCH = 20;

    private final LlmEntityExtractor entityExtractor;
    private final LlmRelationExtractor relationExtractor;
    private final Neo4jGraphStore graphStore;

    public GraphBuilder(String dashscopeApiKey, String neo4jUri, String neo4jUser, String neo4jPassword) {
        var llm = new DashScopeLlmProvider(dashscopeApiKey);
        this.entityExtractor = new LlmEntityExtractor(llm);
        this.relationExtractor = new LlmRelationExtractor(llm);
        this.graphStore = new Neo4jGraphStore(neo4jUri, neo4jUser, neo4jPassword);
    }

    /**
     * 从 JSONL 构建知识图谱（无进度回调）。
     *
     * @param jsonlPath 清洗产出的 chunks.jsonl 路径
     * @return 构建统计
     */
    public GraphStats build(Path jsonlPath) {
        return build(jsonlPath, null);
    }

    /**
     * 从 JSONL 构建知识图谱（带进度回调）。
     *
     * @param jsonlPath 清洗产出的 chunks.jsonl 路径
     * @param callback  进度回调（可为 null）。每 FLUSH_BATCH 个 chunk 调一次。
     * @return 构建统计
     */
    public GraphStats build(Path jsonlPath, ProgressCallback callback) {
        log.info("图谱构建开始: {}", jsonlPath);

        int totalChunks = 0;
        int processedChunks = 0;
        int totalEntities = 0;
        int totalRelations = 0;
        int failedChunks = 0;
        int filteredByModeration = 0;  // 被内容审核拦截的 chunk 数

        // 待 flush 到 Neo4j 的缓冲区
        Map<String, Entity> pendingEntities = new HashMap<>();
        List<Relation> pendingRelations = new ArrayList<>();

        try {
            List<String> lines = Files.readAllLines(jsonlPath);
            totalChunks = lines.size();

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                try {
                    Map<String, Object> obj = MAPPER.readValue(line, Map.class);
                    String chunkId = (String) obj.get("id");
                    String documentId = (String) obj.get("documentId");
                    String sourcePath = (String) obj.get("sourcePath");
                    String text = (String) obj.get("text");
                    int ordinal = (Integer) obj.get("ordinal");

                    TextChunk chunk = new TextChunk(chunkId, documentId, sourcePath, text, ordinal, null);

                    // 抽取实体（含全局消歧）
                    List<Entity> entities = entityExtractor.extract(chunk);
                    for (Entity e : entities) {
                        pendingEntities.merge(e.normalizedKey(), e, (existing, newer) -> {
                            List<String> aliases = new ArrayList<>(existing.aliases());
                            for (String a : newer.aliases()) {
                                if (!aliases.contains(a)) aliases.add(a);
                            }
                            return new Entity(existing.id(), existing.name(), existing.type(),
                                    existing.normalizedKey(), aliases);
                        });
                    }

                    // 抽取关系（基于本 chunk 实体）
                    List<Relation> relations = relationExtractor.extract(chunk, entities);
                    pendingRelations.addAll(relations);

                    processedChunks++;
                } catch (ContentModerationException e) {
                    // 内容审核拦截——不重试不阻塞，直接跳过这个 chunk
                    filteredByModeration++;
                    processedChunks++;
                    log.debug("Chunk {} 被内容审核拦截，跳过", i);
                } catch (Exception e) {
                    log.warn("Chunk {} 图谱抽取失败: {}", i, e.getMessage());
                    failedChunks++;
                    processedChunks++;
                }

                // 每 FLUSH_BATCH 个 chunk：流式写 Neo4j + 进度回调
                if ((i + 1) % FLUSH_BATCH == 0) {
                    flushToStore(pendingEntities, pendingRelations);
                    totalEntities += countNewEntities(pendingEntities);
                    totalRelations += pendingRelations.size();
                    int curEntities = totalEntities;
                    int curRelations = totalRelations;
                    log.info("图谱构建进度: {}/{} chunks, {} 实体, {} 关系, {} 被审核拦截",
                            i + 1, totalChunks, curEntities, curRelations, filteredByModeration);
                    if (callback != null) {
                        callback.onProgress(processedChunks, totalChunks, curEntities, curRelations, filteredByModeration);
                    }
                    pendingEntities.clear();
                    pendingRelations.clear();
                }
            }

            // 处理剩余不足一批的
            if (!pendingEntities.isEmpty() || !pendingRelations.isEmpty()) {
                flushToStore(pendingEntities, pendingRelations);
                totalEntities += countNewEntities(pendingEntities);
                totalRelations += pendingRelations.size();
                pendingEntities.clear();
                pendingRelations.clear();
            }

            if (callback != null) {
                callback.onProgress(processedChunks, totalChunks, totalEntities, totalRelations, filteredByModeration);
            }

        } catch (Exception e) {
            log.error("图谱构建失败: {}", e.getMessage(), e);
        } finally {
            graphStore.close();
        }

        GraphStats stats = new GraphStats(totalChunks, totalEntities, totalRelations,
                failedChunks, filteredByModeration);
        log.info("图谱构建完成: {}", stats);
        return stats;
    }

    /** 把当前批次缓冲区里的实体/关系刷入 Neo4j（MERGE 幂等） */
    private void flushToStore(Map<String, Entity> entities, List<Relation> relations) {
        if (entities.isEmpty() && relations.isEmpty()) return;
        try {
            if (!entities.isEmpty()) {
                graphStore.upsertEntities(new ArrayList<>(entities.values()));
            }
            if (!relations.isEmpty()) {
                graphStore.upsertRelations(relations);
            }
        } catch (Exception e) {
            log.warn("流式写入 Neo4j 失败（本批数据已丢失）: {}", e.getMessage());
        }
    }

    /** 统计本批新增的实体数（用于进度展示） */
    private int countNewEntities(Map<String, Entity> entities) {
        return entities.size();
    }

    /** 进度回调接口 */
    @FunctionalInterface
    public interface ProgressCallback {
        /**
         * @param processed           已处理 chunk 数
         * @param total               总 chunk 数
         * @param entities            累计已写入实体数
         * @param relations           累计已写入关系数
         * @param filteredByModeration 累计被内容审核拦截的 chunk 数
         */
        void onProgress(int processed, int total, int entities, int relations, int filteredByModeration);
    }

    /**
     * 内容审核异常——表示该 chunk 被 DashScope 内容审核拦截。
     * 不应触发重试，由 GraphBuilder 统计后跳过。
     */
    public static class ContentModerationException extends RuntimeException {
        public ContentModerationException(String message) {
            super(message);
        }
    }

    /** 图谱构建统计 */
    public record GraphStats(int totalChunks, int totalEntities, int totalRelations,
                             int failedChunks, int filteredByModeration) {}
}
