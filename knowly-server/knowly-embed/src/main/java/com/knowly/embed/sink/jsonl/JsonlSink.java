package com.knowly.embed.sink.jsonl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.SinkException;
import com.knowly.core.model.ChunkMetadata;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.ChunkSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSONL 产出 Sink（默认）。每行一个 chunk 的结构化 JSON。
 *
 * <p>字段与数据模型对齐，便于程序化消费和导入其他系统。
 *
 * <p><b>跨任务累积语义</b>（分批清洗的核心行为）：
 * <ul>
 *   <li>新文档写入 → <b>追加</b>到已有文件（分批清洗的产物自动累积成全量知识库）</li>
 *   <li>重跑同一文档 → 该文档的旧行被<b>替换</b>（幂等，不会重复入库）</li>
 * </ul>
 * 曾为"每任务首次写入即截断"——分批跑第二批会覆盖第一批的 chunks，已修复。
 */
public class JsonlSink implements ChunkSink {

    private static final Logger log = LoggerFactory.getLogger(JsonlSink.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path outputPath;
    /** 本实例是否已执行过首写（首写负责合并旧文件内容） */
    private boolean isFirstWrite = true;

    public JsonlSink(Path outputPath) {
        this.outputPath = outputPath;
    }

    @Override
    public String type() { return "jsonl"; }

    @Override
    public boolean requiresEmbedding() { return false; }

    @Override
    public synchronized void write(List<TextChunk> chunks) {
        if (chunks.isEmpty()) return;
        try {
            Files.createDirectories(outputPath.getParent());

            if (isFirstWrite && Files.exists(outputPath)) {
                // 首写且文件已存在（分批清洗场景）：剔除本次要写文档的旧行，保留其他文档的行
                Set<String> incomingDocIds = new HashSet<>();
                for (TextChunk c : chunks) incomingDocIds.add(c.documentId());
                rewriteExcluding(incomingDocIds);
                log.debug("JSONL 合并旧文件: {}（替换 {} 个文档的旧行）", outputPath, incomingDocIds.size());
            }
            isFirstWrite = false;

            StringBuilder sb = new StringBuilder();
            for (TextChunk chunk : chunks) {
                ObjectNode json = MAPPER.createObjectNode();
                json.put("id", chunk.id());
                json.put("documentId", chunk.documentId());
                json.put("sourcePath", chunk.sourcePath());
                json.put("text", chunk.text());
                json.put("ordinal", chunk.ordinal());

                ChunkMetadata meta = chunk.metadata();
                if (meta != null) {
                    ObjectNode metaNode = json.putObject("metadata");
                    metaNode.put("sectionTitle", meta.sectionTitle() != null ? meta.sectionTitle() : "");
                    metaNode.put("sectionLevel", meta.sectionLevel());
                    metaNode.put("startPage", meta.startPage());
                    metaNode.put("charCount", meta.charCount());
                    if (meta.blockType() != null) {
                        metaNode.put("blockType", meta.blockType().name());
                    }
                }
                sb.append(MAPPER.writeValueAsString(json)).append("\n");
            }
            Files.writeString(outputPath, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("JSONL 写入: {} ({} lines)", outputPath, chunks.size());
        } catch (IOException e) {
            throw new SinkException(ErrorCode.SINK_003, "JSONL 文件写入失败",
                    "file=" + outputPath + ", cause=" + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void deleteByDocument(String documentId) {
        // 过滤重写：剔除该文档的所有行
        if (!Files.exists(outputPath)) return;
        try {
            rewriteExcluding(Set.of(documentId));
            log.debug("JSONL 按文档删除: {} from {}", documentId, outputPath);
        } catch (IOException e) {
            throw new SinkException(ErrorCode.SINK_003, "JSONL 按文档删除失败",
                    "file=" + outputPath + ", doc=" + documentId + ", cause=" + e.getMessage(), e);
        }
    }

    /** 重写文件：剔除指定文档的所有行，保留其余行。
     *  调用方持有实例锁（write/deleteByDocument 均已 synchronized）——
     *  流水线多文档并发写同一文件，无锁时 rewrite 与 append 交错会截断行（曾致 jsonl 损坏）。 */
    private void rewriteExcluding(Set<String> excludeDocIds) throws IOException {
        StringBuilder kept = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                if (belongsToExcludedDoc(line, excludeDocIds)) continue;
                kept.append(line).append("\n");
            }
        }
        Files.writeString(outputPath, kept.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** 判断一行 JSONL 是否属于待剔除文档（解析 documentId 字段） */
    private boolean belongsToExcludedDoc(String jsonLine, Set<String> excludeDocIds) {
        try {
            var node = MAPPER.readTree(jsonLine);
            var docId = node.get("documentId");
            return docId != null && excludeDocIds.contains(docId.asText());
        } catch (Exception e) {
            // 解析失败的行保守保留
            return false;
        }
    }

    @Override
    public void close() {
        // 文件型 Sink 无需关闭资源
    }
}
