package com.knowly.clean.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowly.clean.chunking.PreChunker.PreChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link StructureAwareChunking} 和 {@link PreChunker} 单元测试。
 * 覆盖两阶段分段的核心逻辑：标题识别、大块切分、小块合并、碎片处理。
 */
class StructureAwareChunkingTest {

    private StructureAwareChunking chunking = new StructureAwareChunking(500, 50, 50);

    private com.knowly.core.model.RawDocument docWith(String content) {
        return new com.knowly.core.model.RawDocument(
                "doc-test", "/test.md", "test.md", "md",
                content, null, "hash", com.knowly.common.enums.ParseStatus.SUCCESS);
    }

    @Test
    void should_recognize_markdown_heading_as_boundary() {
        PreChunker pc = new PreChunker();
        String content = "# 第一章\n正文段落一\n\n# 第二章\n正文段落二";

        List<PreChunk> chunks = pc.chunk(content);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionTitle()).contains("第一章");
        assertThat(chunks.get(1).sectionTitle()).contains("第二章");
    }

    @Test
    void should_recognize_chinese_numbered_heading() {
        PreChunker pc = new PreChunker();

        List<PreChunk> chunks = pc.chunk("第三章 太阳病\n正文\n第四章 阳明病\n正文");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).sectionTitle()).startsWith("第三章");
    }

    @Test
    void should_not_recognize_overlong_line_as_heading() {
        PreChunker pc = new PreChunker();
        // 构造超过 MAX_HEADING_LENGTH(40) 的单行（50 个汉字）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("字");

        List<PreChunk> chunks = pc.chunk(sb.toString());

        // 超过 40 字 → 不当标题 → 整体是一个 pre-chunk
        assertThat(chunks).hasSize(1);
    }

    @Test
    void should_split_large_prechunk_by_max_size() {
        chunking = new StructureAwareChunking(100, 10, 30);
        // 生成超过 maxSize 的连续文本（无标题边界）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("这是第").append(i).append("段话。");

        List<com.knowly.core.model.TextChunk> chunks = chunking.chunk(docWith(sb.toString()));

        assertThat(chunks.size()).isGreaterThan(1);
        // 每个 chunk 不应远超 maxSize（允许边界找句号时略超）
        for (var c : chunks) {
            assertThat(c.text().length()).isLessThanOrEqualTo(150);
        }
    }

    @Test
    void should_produce_chunks_with_correct_id_format() {
        List<com.knowly.core.model.TextChunk> chunks = chunking.chunk(
                docWith("短文本内容"));

        assertThat(chunks).isNotEmpty();
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).id()).startsWith("doc-test#");
        }
    }

    @Test
    void should_handle_empty_content() {
        List<com.knowly.core.model.TextChunk> chunks = chunking.chunk(docWith(""));

        assertThat(chunks).isEmpty();
    }

    @Test
    void should_handle_single_short_paragraph() {
        List<com.knowly.core.model.TextChunk> chunks = chunking.chunk(
                docWith("只有一个短段落。"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("短段落");
    }

    @Test
    void should_attach_section_title_to_chunk_metadata() {
        String content = "# 方剂总论\n桂枝汤组成。\n\n# 各论\n麻黄汤组成。";

        List<com.knowly.core.model.TextChunk> chunks = chunking.chunk(docWith(content));

        assertThat(chunks).isNotEmpty();
        // 标题行作为 sectionTitle（含 # 前缀），后续段落继承该章节
        boolean hasZonglun = chunks.stream()
                .anyMatch(c -> c.metadata().sectionTitle() != null
                        && c.metadata().sectionTitle().contains("方剂总论"));
        assertThat(hasZonglun).isTrue();
    }

    @Test
    void should_reject_invalid_args() {
        assertThatThrownBy_maxSize_zero();
        assertThatThrownBy_overlap_exceeds_maxSize();
    }

    private void assertThatThrownBy_maxSize_zero() {
        try {
            new StructureAwareChunking(0, 0, 0);
            throw new AssertionError("应抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    private void assertThatThrownBy_overlap_exceeds_maxSize() {
        try {
            new StructureAwareChunking(100, 100, 50);  // overlap == maxSize
            throw new AssertionError("应抛异常");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
