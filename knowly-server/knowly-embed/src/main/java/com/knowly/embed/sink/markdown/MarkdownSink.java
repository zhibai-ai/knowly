package com.knowly.embed.sink.markdown;

import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.SinkException;
import com.knowly.core.model.ChunkMetadata;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.ChunkSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Markdown 产出 Sink（默认）。
 *
 * <p>按章节组织 Markdown 文件，chunk 边界用 HTML 注释嵌入（人眼不可见但程序可解析）。
 * 一个产物两种用法：①直接拖进 LLM 对话；②knowly reembed 回灌成向量库。
 */
public class MarkdownSink implements ChunkSink {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSink.class);

    private final Path outputDir;
    private final boolean includeChunkMarkers;
    private final boolean keepSourceTree;

    /**
     * 创建 Markdown Sink。
     *
     * @param outputDir          输出目录（如 ./data/kb/00-clean）
     * @param includeChunkMarkers 是否在 Markdown 中嵌入 chunk 边界 HTML 注释
     * @param keepSourceTree     是否按原文档目录结构组织输出
     */
    public MarkdownSink(Path outputDir, boolean includeChunkMarkers, boolean keepSourceTree) {
        this.outputDir = outputDir;
        this.includeChunkMarkers = includeChunkMarkers;
        this.keepSourceTree = keepSourceTree;
    }

    @Override
    public String type() { return "markdown"; }

    @Override
    public boolean requiresEmbedding() { return false; }

    @Override
    public void write(List<TextChunk> chunks) {
        if (chunks.isEmpty()) return;
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new SinkException(ErrorCode.SINK_003, "Markdown 输出目录创建失败",
                    "dir=" + outputDir + ", cause=" + e.getMessage(), e);
        }

        // 按 documentId 分组，每个文档产一个 .md 文件
        Map<String, List<TextChunk>> byDoc = new HashMap<>();
        for (TextChunk chunk : chunks) {
            byDoc.computeIfAbsent(chunk.documentId(), k -> new java.util.ArrayList<>()).add(chunk);
        }

        for (Map.Entry<String, List<TextChunk>> entry : byDoc.entrySet()) {
            String docId = entry.getKey();
            List<TextChunk> docChunks = entry.getValue();
            docChunks.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));

            Path mdFile = outputDir.resolve(sanitizeFileName(docChunks.get(0).sourcePath()) + ".md");
            try {
                writeMarkdownFile(mdFile, docChunks);
                log.debug("Markdown 写入: {} ({} chunks)", mdFile, docChunks.size());
            } catch (IOException e) {
                throw new SinkException(ErrorCode.SINK_003, "Markdown 文件写入失败",
                        "file=" + mdFile + ", cause=" + e.getMessage(), e);
            }
        }
    }

    private void writeMarkdownFile(Path mdFile, List<TextChunk> chunks) throws IOException {
        StringBuilder sb = new StringBuilder();
        String currentSection = null;

        for (TextChunk chunk : chunks) {
            ChunkMetadata meta = chunk.metadata();
            // 章节标题变化时输出标题
            if (meta != null && meta.sectionTitle() != null && !meta.sectionTitle().equals(currentSection)) {
                currentSection = meta.sectionTitle();
                String prefix = "#".repeat(Math.max(1, meta.sectionLevel()));
                sb.append(prefix).append(" ").append(currentSection).append("\n\n");
            }

            // chunk 边界标记（HTML 注释，人眼不可见但程序可解析）
            if (includeChunkMarkers && meta != null) {
                sb.append(String.format(
                        "<!-- knowly:chunk id=\"%s\" level=\"%d\" page=\"%d\" char_count=\"%d\" -->\n",
                        chunk.id(), meta.sectionLevel(), meta.startPage(), meta.charCount()));
            }

            // 处理文本中的图片标记 → 转成 Markdown 图片引用（用绝对路径，兼容所有编辑器）
            String text = chunk.text();
            String outputDirStr = outputDir.toAbsolutePath().toString();
            text = text.replaceAll(
                    "<!-- knowly:image page=\"(\\d+)\" file=\"([^\"]+)\" alt=\"([^\"]*)\" -->",
                    "![$3](" + outputDirStr + "/$2)\n");

            sb.append(text).append("\n\n");
        }

        Files.writeString(mdFile, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 把源文件路径转成安全的文件名（去路径分隔符） */
    private String sanitizeFileName(String sourcePath) {
        if (sourcePath == null) return "unknown";
        String name = Path.of(sourcePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @Override
    public void deleteByDocument(String documentId) {
        // Markdown 按文档 ID 命名的文件直接删
        // v0.1 简化：删除整个输出目录下包含该 documentId 的文件
        // [待完善] 需要维护 documentId → 文件名映射
        log.debug("deleteByDocument: {} (MarkdownSink v0.1 简化实现)", documentId);
    }

    @Override
    public void close() {
        // 文件型 Sink 无需关闭资源
    }
}
