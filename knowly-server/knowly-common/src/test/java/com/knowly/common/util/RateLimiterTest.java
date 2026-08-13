package com.knowly.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link RateLimiter} 单元测试。覆盖限流、超时、并发安全、参数校验。
 */
class RateLimiterTest {

    @Test
    void should_reject_invalid_qps() {
        assertThatThrownBy(() -> RateLimiter.create(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimiter.create(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_allow_burst_up_to_qps_immediately() {
        // 启动时桶满，QPS=100 应能立即获取若干令牌不阻塞
        RateLimiter limiter = RateLimiter.create(100);

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            limiter.acquire();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 50 个令牌在满桶时应几乎瞬时（< 200ms 容忍调度抖动）
        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void should_block_when_bucket_empty() {
        // QPS=2，启动桶有 2 个令牌（突发）。取 5 个：前 2 个免费，后 3 个按速率等待。
        // 令牌桶特性：等待期间持续补充令牌，实际阻塞时间约 (N-突发)/qps 到 N/qps 之间。
        // 关键验证点：必须阻塞（不能瞬时完成），且在合理范围（不能像旧实现那样 37s）。
        RateLimiter limiter = RateLimiter.create(2);

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 5 令牌 / 2 QPS，理论 1.5-2.5s（取决于等待期补充交互）。
        // 旧实现曾阻塞 37s（已修复），现在应落在 [800, 3000] 内。
        assertThat(elapsedMs)
                .as("取 5 个令牌/QPS=2 应阻塞，实际 %dms（旧实现曾 37s）", elapsedMs)
                .isBetween(800L, 3000L);
    }

    @Test
    void should_try_acquire_return_false_when_timeout() throws InterruptedException {
        RateLimiter limiter = RateLimiter.create(1);  // 1 QPS

        // 先耗尽初始令牌
        limiter.acquire();

        // 立即再取，超时 100ms 应失败（令牌不足且补充需 1s）
        boolean ok = limiter.tryAcquire(Duration.ofMillis(100));
        assertThat(ok).isFalse();
    }

    @Test
    void should_try_acquire_return_true_when_tokens_available() {
        RateLimiter limiter = RateLimiter.create(100);
        boolean ok = limiter.tryAcquire(Duration.ofSeconds(1));
        assertThat(ok).isTrue();
    }

    @Test
    void should_be_thread_safe_under_concurrency() throws InterruptedException {
        // 并发 10 线程各取 10 次，QPS=1000，总应完成且不死锁
        RateLimiter limiter = RateLimiter.create(1000);
        int threads = 10;
        int perThread = 10;
        AtomicInteger acquired = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < perThread; j++) {
                        limiter.acquire();
                        acquired.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(acquired.get()).isEqualTo(threads * perThread);
    }

    @Test
    void should_acquire_zero_tokens_is_noop() {
        RateLimiter limiter = RateLimiter.create(1);
        // acquire(0) 不应阻塞也不消耗令牌
        limiter.acquire(0);
        // 仍能取到初始令牌
        assertThat(limiter.tryAcquire(Duration.ofMillis(50))).isTrue();
    }
}
