package com.knowly.common.util;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 统一重试工具。所有可重试操作复用此模板，禁止散落各处手写重试。
 *
 * <p>设计要点：
 * <ul>
 *   <li>指数退避 + 抖动（防惊群）</li>
 *   <li>可重试异常白名单（非白名单异常立即抛出）</li>
 *   <li>单次超时 + 总重试次数可配</li>
 * </ul>
 *
 * <p>用法：
 * <pre>{@code
 * RetryTemplate retry = RetryTemplate.builder()
 *     .maxAttempts(3)
 *     .exponentialBackoff(Duration.ofMillis(500), 2.0, Duration.ofSeconds(10))
 *     .retryOn(IOException.class, RateLimitException.class)
 *     .build();
 *
 * String result = retry.execute(() -> embeddingProvider.embed(text));
 * }</pre>
 */
public final class RetryTemplate {

    private final int maxAttempts;
    private final long initialDelayMillis;
    private final double backoffMultiplier;
    private final long maxDelayMillis;
    private final Set<Class<? extends Throwable>> retryOnExceptions;

    private RetryTemplate(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelayMillis = builder.initialDelayMillis;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.maxDelayMillis = builder.maxDelayMillis;
        this.retryOnExceptions = builder.retryOnExceptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 执行可重试操作。
     *
     * @param action 要执行的操作
     * @return 操作结果
     * @throws RuntimeException 重试耗尽后抛出最后一次异常
     */
    public <T> T execute(Supplier<T> action) {
        Throwable lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                lastException = e;
                if (!shouldRetry(e) || attempt >= maxAttempts) {
                    throw e;
                }
                long delay = calculateDelay(attempt);
                sleep(delay);
            }
        }
        // 理论上不会走到这里
        throw new RuntimeException("重试耗尽", lastException);
    }

    /**
     * 执行无返回值的可重试操作。
     */
    public void executeRunnable(Runnable action) {
        execute(() -> {
            action.run();
            return null;
        });
    }

    /** 判断异常是否在可重试白名单内 */
    private boolean shouldRetry(Throwable e) {
        if (retryOnExceptions.isEmpty()) {
            return true;  // 未配白名单则所有异常都重试
        }
        for (Class<? extends Throwable> retryType : retryOnExceptions) {
            if (retryType.isInstance(e)) {
                return true;
            }
        }
        return false;
    }

    /** 计算第 attempt 次重试的等待时间（指数退避 + 抖动） */
    private long calculateDelay(int attempt) {
        // 指数退避：initialDelay * multiplier^(attempt-1)
        double delay = initialDelayMillis * Math.pow(backoffMultiplier, attempt - 1);
        delay = Math.min(delay, maxDelayMillis);
        // 抖动：在 [0.5*delay, 1.5*delay) 范围内随机，防惊群
        double jitter = 0.5 + ThreadLocalRandom.current().nextDouble();  // [0.5, 1.5)
        return (long) (delay * jitter);
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ────────────────────────────────────────────────────────────
    // Builder
    // ────────────────────────────────────────────────────────────

    public static final class Builder {
        private int maxAttempts = 3;
        private long initialDelayMillis = 1000;  // 默认 1 秒
        private double backoffMultiplier = 2.0;   // 默认翻倍
        private long maxDelayMillis = 30_000;     // 默认上限 30 秒
        private final Set<Class<? extends Throwable>> retryOnExceptions = new HashSet<>();

        private Builder() {}

        /** 最大尝试次数（含首次）。默认 3 */
        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** 固定间隔退避（不指数增长） */
        public Builder fixedBackoff(Duration delay) {
            this.initialDelayMillis = delay.toMillis();
            this.backoffMultiplier = 1.0;
            this.maxDelayMillis = delay.toMillis();
            return this;
        }

        /** 指数退避 */
        public Builder exponentialBackoff(Duration initialDelay, double multiplier, Duration maxDelay) {
            this.initialDelayMillis = initialDelay.toMillis();
            this.backoffMultiplier = multiplier;
            this.maxDelayMillis = maxDelay.toMillis();
            return this;
        }

        /** 添加可重试异常类型 */
        @SafeVarargs
        public final Builder retryOn(Class<? extends Throwable>... exceptionTypes) {
            this.retryOnExceptions.addAll(Arrays.asList(exceptionTypes));
            return this;
        }

        public RetryTemplate build() {
            return new RetryTemplate(this);
        }
    }
}
