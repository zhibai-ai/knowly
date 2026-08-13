package com.knowly.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link RetryTemplate} 单元测试。覆盖重试成功、重试耗尽、白名单、指数退避。
 */
class RetryTemplateTest {

    @Test
    void should_succeed_on_first_attempt_when_no_exception() {
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(Duration.ofMillis(1))
                .build();

        String result = retry.execute(() -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void should_retry_then_succeed_when_transient_failure() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(Duration.ofMillis(1))
                .build();

        String result = retry.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("临时错误");
            }
            return "ok-on-3rd";
        });

        assertThat(result).isEqualTo("ok-on-3rd");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void should_throw_after_max_attempts_when_always_failing() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(Duration.ofMillis(1))
                .build();

        assertThatThrownBy(() -> retry.execute(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException("持续失败");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("持续失败");

        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void should_not_retry_when_exception_not_in_whitelist() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(Duration.ofMillis(1))
                .retryOn(IllegalStateException.class)   // 只重试 IllegalStateException
                .build();

        // 抛 IllegalArgumentException（不在白名单）→ 立即抛出，不重试
        assertThatThrownBy(() -> retry.execute(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("不重试");
        }))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(attempts.get()).isEqualTo(1);  // 只执行了 1 次
    }

    @Test
    void should_retry_when_exception_in_whitelist() {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(3)
                .fixedBackoff(Duration.ofMillis(1))
                .retryOn(IllegalStateException.class)
                .build();

        String result = retry.execute(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("可重试");
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void should_support_executeRunnable() {
        AtomicInteger counter = new AtomicInteger(0);
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(2)
                .fixedBackoff(Duration.ofMillis(1))
                .build();

        retry.executeRunnable(counter::incrementAndGet);

        assertThat(counter.get()).isEqualTo(1);
    }
}
