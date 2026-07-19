package com.knowly.embed.sink.qdrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.SinkException;
import com.knowly.core.model.EmbeddedChunk;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.ChunkSink;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Qdrant 向量库 Sink（默认推荐）。
 *
 * <p>通过 Qdrant REST API（/points/upsert）写入向量。
 * 不依赖 Qdrant Java 客户端（gRPC API 太重），用 JDK HttpClient 直接调 REST API。
 *
 * <p>幂等：用 chunk id 的哈希作为 point id，覆盖写入，中断重跑无重复。
 */
public class QdrantSink implements ChunkSink {

    private static final Logger log = LoggerFactory.getLogger(QdrantSink.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String collectionName;
    private final int dimension;
    private final int batchSize;

    /**
     * 创建 Qdrant Sink。
     *
     * @param host           Qdrant 主机（如 localhost）
     * @param port           Qdrant HTTP 端口（如 6333）
     * @param collectionName collection 名称
     * @param dimension      向量维度（1024）
     * @param batchSize      批量写入大小（默认 100）
     */
    public QdrantSink(String host, int port, String collectionName, int dimension, int batchSize) {
        this.baseUrl = "http://" + host + ":" + port;
        this.collectionName = collectionName;
        this.dimension = dimension;
        this.batchSize = batchSize;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        ensureCollection();
        log.info("QdrantSink 初始化: baseUrl={}, collection={}, dim={}",
                baseUrl, collectionName, dimension);
    }

    @Override
    public String type() { return "qdrant"; }

    @Override
    public boolean requiresEmbedding() { return true; }

    @Override
    public void write(List<TextChunk> chunks) {
        // Qdrant 只存 EmbeddedChunk，write 空实现
    }

    @Override
    public void writeEmbedded(List<EmbeddedChunk> chunks) {
        if (chunks.isEmpty()) return;

        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<EmbeddedChunk> batch = chunks.subList(i, end);
            try {
                upsertPoints(batch);
                log.debug("Qdrant 批量写入: {}/{}", end, chunks.size());
            } catch (Exception e) {
                throw new SinkException(ErrorCode.SINK_001, "Qdrant 写入失败",
                        "batch=" + i + "-" + end + ", cause=" + e.getMessage(), e);
            }
        }
    }

    @Override
    public void deleteByDocument(String documentId) {
        try {
            Map<String, Object> filter = Map.of(
                    "must", List.of(Map.of(
                            "key", "document_id",
                            "match", Map.of("value", documentId)
                    ))
            );
            Map<String, Object> body = Map.of("filter", filter);
            String json = MAPPER.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/collections/" + collectionName + "/points/delete"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.debug("Qdrant 按文档删除: {}", documentId);
        } catch (Exception e) {
            log.warn("Qdrant 删除失败: docId={}, cause={}", documentId, e.getMessage());
        }
    }

    @Override
    public void close() {
        // HttpClient 无需显式关闭
    }

    // ────────────────────────────────────────────────────────────
    // 内部方法
    // ────────────────────────────────────────────────────────────

    /** 确保 collection 存在 */
    private void ensureCollection() {
        try {
            // 先查 collection 是否存在
            HttpRequest checkReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/collections/" + collectionName))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(checkReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return;  // 已存在

            // 不存在 → 创建
            log.info("创建 Qdrant collection: {}, dim={}", collectionName, dimension);
            Map<String, Object> createBody = Map.of(
                    "vectors", Map.of(
                            "size", dimension,
                            "distance", "Cosine"
                    )
            );
            String json = MAPPER.writeValueAsString(createBody);
            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/collections/" + collectionName))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SinkException(ErrorCode.SINK_002, "Qdrant collection 创建失败",
                    "collection=" + collectionName + ", cause=" + e.getMessage(), e);
        }
    }

    /** 批量 upsert points */
    private void upsertPoints(List<EmbeddedChunk> batch) throws Exception {
        List<Map<String, Object>> points = new ArrayList<>(batch.size());
        for (EmbeddedChunk chunk : batch) {
            // point id 用 chunk id 的哈希（保证幂等）
            String pointId = chunk.id().hashCode() + "";
            // 向量
            List<Float> vector = toFloatList(chunk.embedding());
            // payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("text", chunk.text());
            payload.put("document_id", chunk.documentId());
            payload.put("chunk_id", chunk.id());
            if (chunk.metadata() != null) payload.putAll(chunk.metadata());

            points.add(Map.of(
                    "id", Integer.parseInt(pointId),
                    "vector", vector,
                    "payload", payload
            ));
        }

        Map<String, Object> body = Map.of("points", points);
        String json = MAPPER.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/collections/" + collectionName + "/points?wait=true"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new SinkException(ErrorCode.SINK_001, "Qdrant upsert 失败",
                    "status=" + resp.statusCode() + ", body=" + resp.body());
        }
    }

    /** float[] → List<Float>（Qdrant JSON 需要） */
    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}
