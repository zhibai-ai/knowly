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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSONL 产出 Sink（默认）。每行一个 chunk 的结构化 JSON。
 *
 * <p>字段与数据模型对齐，便于程序化消费和导入其他系统。
 */
public class JsonlSink implements ChunkSink {

    private static final Logger log = LoggerFactory.getLogger(JsonlSink.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path outputPath;

    public JsonlSink(Path outputPath) {
        this.outputPath = outputPath;
    }

    @Override
    public String type() { return "jsonl"; }

    @Override
    public boolean requiresEmbedding() { return false; }

    @Override
    public void write(List<TextChunk> chunks) {
        if (chunks.isEmpty()) return;
        try {
            Files.createDirectories(outputPath.getParent());
            // 追加模式（多次写入同文件）
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
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("JSONL 写入: {} ({} lines)", outputPath, chunks.size());
        } catch (IOException e) {
            throw new SinkException(ErrorCode.SINK_003, "JSONL 文件写入失败",
                    "file=" + outputPath + ", cause=" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteByDocument(String documentId) {
        // [待完善] JSONL 不支持按文档精确删除行。
        // v0.1 简化：增量更新时整体重写（重新跑一遍该文档）。
        log.debug("deleteByDocument: {} (JsonlSink v0.1 简化：增量更新时整体重写)", documentId);
    }

    @Override
    public void close() {
        // 文件型 Sink 无需关闭资源
    }
}
