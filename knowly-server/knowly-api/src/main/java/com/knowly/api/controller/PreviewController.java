package com.knowly.api.controller;

import com.knowly.api.common.ApiResponse;
import com.knowly.clean.chunking.StructureAwareChunking;
import com.knowly.clean.cleaner.DefaultTextCleaner;
import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.DocumentParser;
import com.knowly.ingest.CompositeParser;
import org.springframework.web.bind.annotation.*;
import java.nio.file.Path;
import java.util.*;

/**
 * 分段预览接口——Web 调优工作台的核心价值。
 *
 * <p>用户调整分段参数后，提交一个文件+参数，后端只跑摄取+清洗+分段（不 embed、不落库），
 * 返回若干 chunk 样本供预览。
 */
@RestController
@RequestMapping("/api/preview")
public class PreviewController {

    private final DocumentParser parser = new CompositeParser();
    private final DefaultTextCleaner cleaner = new DefaultTextCleaner();

    @PostMapping("/chunks")
    public ApiResponse previewChunks(@RequestBody Map<String, Object> body) {
        String sourcePath = (String) body.get("sourcePath");
        if (sourcePath == null) {
            return ApiResponse.error("CONFIG_002", "必须指定 sourcePath");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> chunkingConfig = (Map<String, Object>) body.getOrDefault("chunking", Map.of());
        int maxSize = (int) chunkingConfig.getOrDefault("maxSize", 500);
        int overlap = (int) chunkingConfig.getOrDefault("overlap", 50);
        int minSize = (int) chunkingConfig.getOrDefault("minSize", 50);

        // 只处理单个文件
        Path filePath = Path.of(sourcePath);
        if (!filePath.toFile().exists()) {
            return ApiResponse.error("SEC_003", "文件不存在: " + sourcePath);
        }

        try {
            // 摄取
            RawDocument rawDoc = parser.parse(filePath);
            // 清洗
            String cleaned = cleaner.clean(rawDoc.content(), rawDoc);
            // 组装清洗后的文档（用于分段）
            RawDocument cleanedDoc = new RawDocument(
                    rawDoc.id(), rawDoc.sourcePath(), rawDoc.fileName(),
                    rawDoc.format(), cleaned, rawDoc.metadata(),
                    rawDoc.contentHash(), rawDoc.status());
            // 分段（只用 StructureAwareChunking，preview 不消耗 embedding）
            var chunking = new StructureAwareChunking(maxSize, overlap, minSize);
            List<TextChunk> chunks = chunking.chunk(cleanedDoc);

            // 返回前 20 个 chunk 样本
            int previewLimit = 20;
            boolean truncated = chunks.size() > previewLimit;
            List<TextChunk> previewChunks = truncated ? chunks.subList(0, previewLimit) : chunks;

            // 组装响应（简化 chunk 数据，只返回关键信息）
            List<Map<String, Object>> chunkList = new ArrayList<>();
            for (TextChunk chunk : previewChunks) {
                Map<String, Object> chunkMap = new LinkedHashMap<>();
                chunkMap.put("id", chunk.id());
                chunkMap.put("text", chunk.text());
                chunkMap.put("ordinal", chunk.ordinal());
                chunkMap.put("charCount", chunk.text().length());
                if (chunk.metadata() != null) {
                    chunkMap.put("sectionTitle", chunk.metadata().sectionTitle());
                    chunkMap.put("sectionLevel", chunk.metadata().sectionLevel());
                }
                chunkList.add(chunkMap);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalChunks", chunks.size());
            data.put("previewChunks", chunkList);
            data.put("truncated", truncated);

            return ApiResponse.ok(data);

        } catch (Exception e) {
            return ApiResponse.error("PARSE_001", "预览失败: " + e.getMessage());
        }
    }
}
