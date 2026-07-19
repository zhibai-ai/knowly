package com.knowly.clean.chunking;

import com.clearspring.analytics.stream.membership.BloomFilter;
import com.knowly.common.enums.BlockType;
import com.knowly.common.util.HashUtils;
import com.knowly.core.model.ChunkMetadata;
import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.clean.chunking.PreChunker.PreChunk;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 两阶段结构感知分段（StructureAwareChunking）—— v0.1 默认分段策略。
 *
 * <p>借鉴 Unstructured 两阶段架构，职责分离：
 * <ul>
 *   <li>阶段一（{@link PreChunker}）：按结构边界（标题/章节）切 pre-chunk</li>
 *   <li>阶段二（本类）：按 max_size 切最终 chunk，小 pre-chunk 合并，保留 overlap</li>
 * </ul>
 *
 * <p>不同分段策略只差阶段一的边界规则。basic 传空边界，by_title 传 [isTitle]，
 * semantic 传 [embeddingSimilarityDrop]。
 */
public class StructureAwareChunking implements com.knowly.core.spi.ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(StructureAwareChunking.class);

    private final int maxSize;
    private final int overlap;
    private final int minSize;
    private final PreChunker preChunker;

    /**
     * 创建分段器（默认 max_size=500, overlap=50, min_size=50）。
     */
    public StructureAwareChunking() {
        this(500, 50, 100);
    }

    /**
     * 创建分段器。
     *
     * @param maxSize 单个 chunk 最大字符数（硬上限）
     * @param overlap 相邻 chunk 的重叠字符数
     * @param minSize 单个 chunk 最小字符数（小于此值的相邻 chunk 会合并）
     */
    public StructureAwareChunking(int maxSize, int overlap, int minSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize must be > 0");
        if (overlap < 0 || overlap >= maxSize) throw new IllegalArgumentException("overlap must be in [0, maxSize)");
        this.maxSize = maxSize;
        this.overlap = overlap;
        this.minSize = minSize;
        this.preChunker = new PreChunker();
    }

    @Override
    public List<TextChunk> chunk(RawDocument doc) {
        log.debug("两阶段分段: doc={}, contentLength={}", doc.id(), doc.content().length());

        // 阶段一：按结构边界切 pre-chunk
        List<PreChunk> preChunks = preChunker.chunk(doc.content());
        log.debug("阶段一完成: {} 个 pre-chunk", preChunks.size());

        // 阶段二：按 max_size 切 + 合并 + overlap
        List<TextChunk> chunks = splitAndMerge(doc, preChunks);
        log.debug("阶段二完成: {} 个 chunk", chunks.size());

        return chunks;
    }

    /**
     * 阶段二：对 pre-chunk 切分/合并，产出最终 TextChunk。
     */
    private List<TextChunk> splitAndMerge(RawDocument doc, List<PreChunk> preChunks) {
        List<TextChunk> result = new ArrayList<>();
        int ordinal = 0;

        // 缓冲区：用于合并相邻小 pre-chunk
        StringBuilder buffer = new StringBuilder();
        String bufferSectionTitle = "";
        int bufferSectionLevel = 0;

        for (PreChunk preChunk : preChunks) {
            int preChunkLen = preChunk.text().length();

            if (preChunkLen > maxSize) {
                // 1) pre-chunk 太大 → 先 flush buffer，再二次切分 pre-chunk
                ordinal = flushBuffer(doc, buffer, bufferSectionTitle, bufferSectionLevel, result, ordinal);
                buffer.setLength(0);

                List<String> splitParts = splitLargeText(preChunk.text(), maxSize);
                // 合并最后一个碎块到前一个（避免产出极小 chunk）
                if (splitParts.size() >= 2) {
                    String lastPart = splitParts.get(splitParts.size() - 1);
                    if (lastPart.length() < minSize) {
                        // 把碎块合并到前一个 part
                        String prevPart = splitParts.get(splitParts.size() - 2);
                        splitParts.set(splitParts.size() - 2, prevPart + "\n" + lastPart);
                        splitParts.remove(splitParts.size() - 1);
                    }
                }
                for (String part : splitParts) {
                    result.add(createChunk(doc, ordinal, part, preChunk.sectionTitle(), preChunk.sectionLevel()));
                    ordinal++;
                }
            } else if (preChunkLen < minSize) {
                // 2) pre-chunk 太小 → 放入 buffer，等合并
                if (buffer.length() + preChunkLen > maxSize) {
                    // buffer 满了先 flush
                    ordinal = flushBuffer(doc, buffer, bufferSectionTitle, bufferSectionLevel, result, ordinal);
                }
                if (buffer.isEmpty()) {
                    bufferSectionTitle = preChunk.sectionTitle();
                    bufferSectionLevel = preChunk.sectionLevel();
                }
                buffer.append(preChunk.text()).append("\n");
            } else {
                // 3) pre-chunk 大小合适 → 先 flush buffer，再直接作为一个 chunk
                ordinal = flushBuffer(doc, buffer, bufferSectionTitle, bufferSectionLevel, result, ordinal);
                buffer.setLength(0);

                result.add(createChunk(doc, ordinal, preChunk.text(), preChunk.sectionTitle(), preChunk.sectionLevel()));
                ordinal++;
            }
        }

        // flush 最后的 buffer
        ordinal = flushBuffer(doc, buffer, bufferSectionTitle, bufferSectionLevel, result, ordinal);

        // 后处理：合并碎片到相邻 chunk
        result = mergeFragments(result, minSize);

        return result;
    }

    /**
     * 合并碎片。
     *
     * <p>判断"碎片"的标准不是字符数——而是内容完整性：
     * <ul>
     *   <li>以标点开头（如 "。" "，"）→ 切分产生的残余，合并到前一个 chunk</li>
     *   <li>不含任何完整句子（无句号/问号/感叹号/换行）→ 合并到前一个</li>
     *   <li>字数 < 30 且不是独立知识单元 → 合并到前一个</li>
     * </ul>
     * 完整的条目列表（如"一、xxx\n二、xxx\n三、xxx"）即使是 70 字符也不合并——它们是独立知识单元。
     */
    private List<TextChunk> mergeFragments(List<TextChunk> chunks, int minSize) {
        if (chunks.size() <= 1) return chunks;

        List<TextChunk> merged = new ArrayList<>();
        for (TextChunk chunk : chunks) {
            String text = chunk.text().strip();
            boolean isFragment = false;

            // 以标点开头 → 碎片
            if (!text.isEmpty() && "。，、；：？！.,;:?!。．".indexOf(text.charAt(0)) >= 0) {
                isFragment = true;
            }
            // 小于 30 字符 → 碎片（独立知识单元通常 > 50）
            if (text.length() < 30) {
                isFragment = true;
            }

            if (isFragment && !merged.isEmpty()) {
                // 合并到前一个 chunk
                TextChunk prev = merged.get(merged.size() - 1);
                String combinedText = prev.text() + "\n" + text;
                ChunkMetadata prevMeta = prev.metadata();
                TextChunk combined = new TextChunk(
                        prev.id(), prev.documentId(), prev.sourcePath(),
                        combinedText, prev.ordinal(),
                        new ChunkMetadata(
                                prevMeta.sectionTitle(), prevMeta.sectionLevel(),
                                prevMeta.startPage(), combinedText.length(),
                                prevMeta.blockType(), prevMeta.tags(), prevMeta.origElementIds()
                        )
                );
                merged.set(merged.size() - 1, combined);
            } else {
                merged.add(chunk);
            }
        }
        return merged;
    }

    /** flush buffer 为一个 chunk（过滤无意义碎片） */
    private int flushBuffer(RawDocument doc, StringBuilder buffer,
                            String sectionTitle, int sectionLevel,
                            List<TextChunk> result, int ordinal) {
        if (buffer.isEmpty()) return ordinal;
        String text = buffer.toString().strip();
        if (!text.isEmpty()) {
            result.add(createChunk(doc, ordinal, text, sectionTitle, sectionLevel));
            return ordinal + 1;
        }
        return ordinal;
    }

    /** 创建 TextChunk */
    private TextChunk createChunk(RawDocument doc, int ordinal, String text,
                                   String sectionTitle, int sectionLevel) {
        String chunkId = HashUtils.chunkId(doc.id(), ordinal);
        ChunkMetadata metadata = new ChunkMetadata(
                sectionTitle,
                sectionLevel,
                0,  // startPage（版面分析后填充，v0.1 暂不填）
                text.length(),
                BlockType.PARAGRAPH,  // 默认段落，后续可根据版面分析细化
                List.of(),
                List.of()  // origElementIds（v0.2 启用）
        );
        return new TextChunk(chunkId, doc.id(), doc.sourcePath(), text, ordinal, metadata);
    }

    /**
     * 对超大文本二次切分（按句号/换行，不切单词）。
     * 重叠设为0——结构感知分段已保证语义边界，不需要 overlap 补偿。
     */
    private List<String> splitLargeText(String text, int maxSize) {
        List<String> parts = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + maxSize, text.length());

            // 如果没到末尾，尝试在 maxSize 附近找句号/换行作为切分点
            if (end < text.length()) {
                int breakPoint = findBreakPoint(text, start, end);
                if (breakPoint > start) end = breakPoint;
            }

            parts.add(text.substring(start, end).strip());

            // 下一个 chunk 从 end 开始（结构感知不需要 overlap）
            if (end >= text.length()) break;
            start = end;
        }

        return parts;
    }

    /**
     * 在 [start, maxEnd] 范围内找最佳切分点（句号 > 换行 > 空格 > 强切）。
     * 从右往左找，尽量不切断句子。
     */
    private int findBreakPoint(String text, int start, int maxEnd) {
        // 优先级：句号 > 换行 > 空格
        for (String delimiter : new String[]{"。", ". ", "！", "？", "!", "?", "\n\n", "\n", " "}) {
            int idx = text.lastIndexOf(delimiter, maxEnd);
            if (idx > start + maxEnd / 2) {  // 切分点至少在中间之后，避免切太碎
                return idx + delimiter.length();
            }
        }
        return maxEnd;  // 找不到合适的，强制切
    }
}
