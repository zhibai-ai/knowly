package com.knowly.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link HashUtils} 单元测试。覆盖内容哈希稳定性、documentId 派生、chunkId 格式、jobId 派生。
 */
class HashUtilsTest {

    @Test
    void should_return_stable_hash_when_same_input() {
        // Arrange
        String text = "肺手太阴之脉，起于中焦";

        // Act
        String hash1 = HashUtils.contentHash(text);
        String hash2 = HashUtils.contentHash(text);

        // Assert
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);  // SHA-256 = 64 位十六进制
    }

    @Test
    void should_return_different_hash_when_different_input() {
        String hash1 = HashUtils.contentHash("内容甲");
        String hash2 = HashUtils.contentHash("内容乙");

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void should_derive_document_id_with_prefix() {
        String hash = HashUtils.contentHash("测试内容");

        String docId = HashUtils.documentId(hash);

        assertThat(docId).startsWith("doc-");
        assertThat(docId).hasSize(4 + 12);  // "doc-" + 前 12 位
    }

    @Test
    void should_generate_chunk_id_in_correct_format() {
        String chunkId = HashUtils.chunkId("doc-ab12cd34ef56", 0);

        assertThat(chunkId).isEqualTo("doc-ab12cd34ef56#0");
    }

    @Test
    void should_generate_chunk_id_with_incrementing_ordinal() {
        String id0 = HashUtils.chunkId("doc-aaa", 0);
        String id1 = HashUtils.chunkId("doc-aaa", 1);
        String id2 = HashUtils.chunkId("doc-aaa", 2);

        assertThat(id0).endsWith("#0");
        assertThat(id1).endsWith("#1");
        assertThat(id2).endsWith("#2");
    }

    @Test
    void should_derive_same_job_id_when_same_input_and_config() {
        // 断点续跑的关键：同输入同配置复用 jobId
        String jobId1 = HashUtils.jobId("/data/docs", "config-fingerprint-v1");
        String jobId2 = HashUtils.jobId("/data/docs", "config-fingerprint-v1");

        assertThat(jobId1).isEqualTo(jobId2);
        assertThat(jobId1).startsWith("job-");
    }

    @Test
    void should_derive_different_job_id_when_config_changed() {
        // 配置变化 → 新 jobId（不复用旧断点）
        String jobId1 = HashUtils.jobId("/data/docs", "config-v1");
        String jobId2 = HashUtils.jobId("/data/docs", "config-v2");

        assertThat(jobId1).isNotEqualTo(jobId2);
    }
}
