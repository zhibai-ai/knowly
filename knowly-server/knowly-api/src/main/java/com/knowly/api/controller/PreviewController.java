package com.knowly.api.controller;

import com.knowly.api.common.ApiResponse;
import com.knowly.clean.chunking.StructureAwareChunking;
import com.knowly.clean.cleaner.DefaultTextCleaner;
import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.DocumentParser;
import com.knowly.ingest.CompositeParser;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 分段预览接口——Web 调优工作台的核心价值。
 *
 * <p>用户调整分段参数后，提交一个文件+参数，后端只跑摄取+清洗+分段（不 embed、不落库），
 * 返回若干 chunk 样本供预览。
 *
 * <p>分段参数（maxSize/overlap/minSize）从请求体读取，驱动 StructureAwareChunking 实例化——
 * 不再硬编码默认值。
 */
@RestController
@RequestMapping("/api/preview")
public class PreviewController {

    private final DocumentParser parser = new CompositeParser();
    private final DefaultTextCleaner cleaner = new DefaultTextCleaner();

    @PostMapping("/chunks")
    public ApiResponse previewChunks(@RequestBody Map<String, Object> body) {
        String sourcePath = (String) body.get("sourcePath");
        if (sourcePath == null || sourcePath.isBlank()) {
            return ApiResponse.error("CONFIG_002", "必须指定 sourcePath");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> chunkingConfig = (Map<String, Object>) body.getOrDefault("chunking", Map.of());
        int maxSize = asInt(chunkingConfig.get("maxSize"), 500);
        int overlap = asInt(chunkingConfig.get("overlap"), 50);
        int minSize = asInt(chunkingConfig.get("minSize"), 50);

        // 路径校验（normalize 防穿越）
        Path filePath;
        try {
            filePath = Path.of(sourcePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            return ApiResponse.error("SEC_003", "非法路径: " + sourcePath);
        }
        if (!filePath.toFile().exists()) {
            return ApiResponse.error("SEC_003", "文件不存在: " + filePath);
        }
        // 若是目录：自动取目录下首个支持的文件作为预览样本（分段预览本就不需要全量，
        // 取样本让用户调参即可）。这让"选目录→预览"可用，而非报错。
        if (Files.isDirectory(filePath)) {
            Path sample = findFirstSupportedFile(filePath);
            if (sample == null) {
                return ApiResponse.error("CONFIG_001", "目录下没有可预览的文档文件: " + filePath);
            }
            filePath = sample;   // 用样本文件继续后续预览
        }

        try {
            RawDocument rawDoc = parser.parse(filePath);
            String cleaned = cleaner.clean(rawDoc.content(), rawDoc);
            RawDocument cleanedDoc = new RawDocument(
                    rawDoc.id(), rawDoc.sourcePath(), rawDoc.fileName(),
                    rawDoc.format(), cleaned, rawDoc.metadata(),
                    rawDoc.contentHash(), rawDoc.status());
            var chunking = new StructureAwareChunking(maxSize, overlap, minSize);
            List<TextChunk> chunks = chunking.chunk(cleanedDoc);

            int previewLimit = 20;
            boolean truncated = chunks.size() > previewLimit;
            List<TextChunk> previewChunks = truncated ? chunks.subList(0, previewLimit) : chunks;

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

    /** 支持的预览文件扩展名 */
    private static final List<String> PREVIEW_EXTENSIONS = List.of(
            ".md", ".txt", ".html", ".htm", ".doc", ".docx", ".pdf");

    /** 在目录下找首个支持的文档文件（用于目录预览的样本） */
    private Path findFirstSupportedFile(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase();
                        return PREVIEW_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static int asInt(Object v, int defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }
}
