package com.knowly.clean.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowly.core.model.RawDocument;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.DedupStrategy.DedupResult;
import com.knowly.common.enums.ParseStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 去重策略测试：{@link HashDedupStrategy}（文件级精确）+ {@link MinHashDedupStrategy}（段落级近似）。
 */
class DedupStrategyTest {

    private RawDocument docWith(String id, String content, String hash) {
        return new RawDocument(id, "/test/" + id, id, "txt",
                content, null, hash, ParseStatus.SUCCESS);
    }

    private TextChunk chunkWith(String id, String text) {
        return new TextChunk(id, "doc-1", "/test", text, 0, null);
    }

    // ── HashDedupStrategy（文件级）──

    @Test
    void hash_should_mark_unique_for_first_doc() {
        HashDedupStrategy dedup = new HashDedupStrategy();
        DedupResult result = dedup.check(docWith("doc-1", "内容甲", "hash-a"));

        assertThat(result.isDuplicate()).isFalse();
    }

    @Test
    void hash_should_mark_duplicate_when_same_content_hash() {
        HashDedupStrategy dedup = new HashDedupStrategy();
        dedup.check(docWith("doc-1", "内容甲", "hash-a"));

        // 同 hash 不同 id → 重复
        DedupResult result = dedup.check(docWith("doc-2", "内容甲", "hash-a"));

        assertThat(result.isDuplicate()).isTrue();
        assertThat(result.duplicateOf()).isEqualTo("doc-1");
    }

    @Test
    void hash_should_keep_unique_when_different_hash() {
        HashDedupStrategy dedup = new HashDedupStrategy();
        dedup.check(docWith("doc-1", "内容甲", "hash-a"));

        DedupResult result = dedup.check(docWith("doc-2", "内容乙", "hash-b"));

        assertThat(result.isDuplicate()).isFalse();
    }

    @Test
    void hash_checkChunks_always_unique() {
        HashDedupStrategy dedup = new HashDedupStrategy();
        List<DedupResult> results = dedup.checkChunks(List.of(
                chunkWith("c1", "x"), chunkWith("c2", "y")));

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> !r.isDuplicate());
    }

    // ── MinHashDedupStrategy（段落级近似）──

    @Test
    void minhash_should_mark_near_duplicate_chunks() {
        MinHashDedupStrategy dedup = new MinHashDedupStrategy(0.5);
        String text1 = "桂枝汤由桂枝芍药生姜大枣甘草五味药组成，主治外感风寒表虚证。";
        String text2 = "桂枝汤由桂枝芍药生姜大枣甘草五味药组成，主治外感风寒表虚证。"; // 完全相同

        List<DedupResult> results = dedup.checkChunks(List.of(
                chunkWith("c1", text1), chunkWith("c2", text2)));

        assertThat(results.get(0).isDuplicate()).isFalse();   // 第一个唯一
        assertThat(results.get(1).isDuplicate()).isTrue();    // 第二个重复
        assertThat(results.get(1).duplicateOf()).isEqualTo("c1");
    }

    @Test
    void minhash_should_not_mark_unrelated_chunks() {
        MinHashDedupStrategy dedup = new MinHashDedupStrategy(0.85);
        List<DedupResult> results = dedup.checkChunks(List.of(
                chunkWith("c1", "天行健君子以自强不息地势坤君子以厚德载物"),
                chunkWith("c2", "白日依山尽黄河入海流欲穷千里目更上一层楼")));

        assertThat(results).allMatch(r -> !r.isDuplicate());
    }

    @Test
    void minhash_file_level_check_always_unique() {
        MinHashDedupStrategy dedup = new MinHashDedupStrategy(0.85);
        DedupResult r1 = dedup.check(docWith("d1", "x", "h1"));
        DedupResult r2 = dedup.check(docWith("d2", "x", "h1"));
        assertThat(r1.isDuplicate()).isFalse();
        assertThat(r2.isDuplicate()).isFalse();   // 段落级策略不做文件级
    }

    @Test
    void minhash_should_mark_highly_similar_as_duplicate() {
        // 两段文字高度相似（只差几个字），阈值 0.5 应判重
        MinHashDedupStrategy dedup = new MinHashDedupStrategy(0.5);
        String a = "太阳病发热恶寒头项强痛脉浮者此为太阳中风表虚证桂枝汤主之";
        String b = "太阳病发热恶寒头项强痛脉浮者此为太阳中风表虚证麻黄汤主之";

        List<DedupResult> results = dedup.checkChunks(List.of(
                chunkWith("a", a), chunkWith("b", b)));

        assertThat(results.get(1).isDuplicate()).isTrue();
    }

    @Test
    void minhash_should_handle_empty_text() {
        MinHashDedupStrategy dedup = new MinHashDedupStrategy(0.85);
        List<DedupResult> results = dedup.checkChunks(List.of(
                chunkWith("empty", "")));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isDuplicate()).isFalse();
    }
}
