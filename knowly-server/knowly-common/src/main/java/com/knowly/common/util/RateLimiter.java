package com.knowly.common.util;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 令牌桶限流器。
 *
 * <p>用途：embedding API 等 QPS 受限场景，控制调用速率，防止打爆 API。
 *
 * <p><b>算法</b>：经典令牌桶（攒存令牌 + 按速率补充）。
 * <ul>
 *   <li>{@code intervalNanos = 1s / qps}：每个令牌的生产周期</li>
 *   <li>{@code storedPermits}：当前攒存的令牌数（浮点，平滑补充），上限 qps</li>
 *   <li>启动时 storedPermits = qps（桶满，允许初始突发 qps 个请求立即通过）</li>
 *   <li>请求 N 个令牌：先用攒存抵扣，不足的缺口按速率等待产生</li>
 * </ul>
 *
 * <p>该实现是线程安全的（{@link ReentrantLock}）。
 */
public final class RateLimiter {

    private final long intervalNanos;   // 每个令牌的生产周期（纳秒）
    private final double maxPermits;    // 桶容量（= qps，攒存上限）
    private final ReentrantLock lock = new ReentrantLock();

    private double storedPermits;       // 当前攒存的令牌数
    private long lastRefillNanos;       // 上次补充令牌的时间点

    /**
     * 创建限流器。
     *
     * @param qps 每秒允许的请求数（>0）
     */
    public static RateLimiter create(int qps) {
        if (qps <= 0) {
            throw new IllegalArgumentException("qps must be > 0, got: " + qps);
        }
        return new RateLimiter(qps);
    }

    private RateLimiter(int qps) {
        // 浮点计算后取整，避免 1e9/qps 整数除法在大 qps 时丢精度
        this.intervalNanos = Math.max(1L, (long) (1_000_000_000.0 / qps));
        this.maxPermits = qps;
        this.storedPermits = qps;   // 启动桶满，允许突发
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * 阻塞获取 1 个令牌。无令牌时睡眠等待。
     */
    public void acquire() {
        acquire(1);
    }

    /**
     * 阻塞获取 N 个令牌。
     *
     * @param tokens 需要的令牌数
     */
    public void acquire(int tokens) {
        if (tokens <= 0) return;
        long waitNanos;
        lock.lock();
        try {
            waitNanos = reserve(tokens);
        } finally {
            lock.unlock();
        }
        sleepNanos(waitNanos);
    }

    /**
     * 预占 tokens 个令牌，返回需等待的纳秒数。
     *
     * <p>核心步骤：
     * <ol>
     *   <li>补充：按自上次补充以来流逝的时间，按速率产生新令牌（不超过桶容量）</li>
     *   <li>抵扣：用攒存的令牌抵扣本次请求，不足部分记为 deficit</li>
     *   <li>等待：deficit × intervalNanos 即需等待的时间</li>
     * </ol>
     */
    private long reserve(int tokens) {
        long now = System.nanoTime();
        // 1) 补充令牌（按流逝时间）
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            double newPermits = (double) elapsed / intervalNanos;
            storedPermits = Math.min(storedPermits + newPermits, maxPermits);
            lastRefillNanos = now;
        }
        // 2) 用攒存抵扣，不足的缺口需等待产生
        double usedFromStored = Math.min(tokens, storedPermits);
        double deficit = tokens - usedFromStored;   // 缺口令牌数
        storedPermits -= usedFromStored;
        // 3) 等待时间 = 缺口 × 每个令牌的周期
        return (long) (deficit * intervalNanos);
    }

    /**
     * 尝试在超时时间内获取令牌。
     *
     * @param timeout 超时时间
     * @return true 获取成功
     */
    public boolean tryAcquire(Duration timeout) {
        return tryAcquire(1, timeout);
    }

    /**
     * 尝试在超时时间内获取 N 个令牌。
     *
     * <p>注意：超时失败时不消费令牌（避免令牌泄漏）。
     *
     * @param tokens  需要的令牌数
     * @param timeout 超时时间
     * @return true 获取成功
     */
    public boolean tryAcquire(int tokens, Duration timeout) {
        if (tokens <= 0) return true;
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + timeout.toNanos();
        // 先预算等待时间（不实际扣令牌），判断是否能在 deadline 内完成
        long projectedWaitNanos;
        lock.lock();
        try {
            projectedWaitNanos = reserve(tokens);
            // 若预算等待超时，归还本次预扣的令牌
            if (projectedWaitNanos > (deadlineNanos - System.nanoTime())) {
                // 归还：把刚扣的加回攒存
                storedPermits = Math.min(storedPermits + tokens, maxPermits);
                return false;
            }
        } finally {
            lock.unlock();
        }
        sleepNanos(projectedWaitNanos);
        return true;
    }

    private static void sleepNanos(long nanos) {
        if (nanos <= 0) return;
        long millis = nanos / 1_000_000;
        int remainderNanos = (int) (nanos % 1_000_000);
        try {
            Thread.sleep(millis, remainderNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
