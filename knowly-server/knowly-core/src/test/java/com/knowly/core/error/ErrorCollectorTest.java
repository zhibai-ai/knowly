package com.knowly.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link ErrorCollector} 单元测试。覆盖记录、计数、查询。
 */
class ErrorCollectorTest {

    @Test
    void should_be_empty_initially() {
        ErrorCollector collector = new ErrorCollector();

        assertThat(collector.hasFailures()).isFalse();
        assertThat(collector.failureCount()).isEqualTo(0);
        assertThat(collector.getFailures()).isEmpty();
    }

    @Test
    void should_record_failure() {
        ErrorCollector collector = new ErrorCollector();

        collector.record("/docs/a.pdf", "doc-1", "INGEST", "PARSE_001", "PDF 损坏");

        assertThat(collector.hasFailures()).isTrue();
        assertThat(collector.failureCount()).isEqualTo(1);
        FailureRecord record = collector.getFailures().get(0);
        assertThat(record.filePath()).isEqualTo("/docs/a.pdf");
        assertThat(record.documentId()).isEqualTo("doc-1");
        assertThat(record.stage()).isEqualTo("INGEST");
        assertThat(record.errorCode()).isEqualTo("PARSE_001");
        assertThat(record.message()).contains("PDF 损坏");
    }

    @Test
    void should_record_multiple_failures_in_order() {
        ErrorCollector collector = new ErrorCollector();
        collector.record("/a", null, "INGEST", "E1", "失败1");
        collector.record("/b", null, "CLEAN", "E2", "失败2");
        collector.record("/c", null, "SINK", "E3", "失败3");

        assertThat(collector.failureCount()).isEqualTo(3);
        assertThat(collector.getFailures()).extracting(FailureRecord::filePath)
                .containsExactly("/a", "/b", "/c");
    }

    @Test
    void should_record_with_null_document_id() {
        ErrorCollector collector = new ErrorCollector();
        // 解析失败时可能还没有 documentId
        collector.record("/a.pdf", null, "INGEST", "PARSE_001", "无法解析");

        assertThat(collector.getFailures().get(0).documentId()).isNull();
        assertThat(collector.failureCount()).isEqualTo(1);
    }
}
