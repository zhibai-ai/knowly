package com.knowly.common.util;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 令牌桶限流器。
 *
 * <p>用途：embedding API 等 QPS 受限场景，控制调用速率，防止打爆 API。
 *
 * <p>算法：令牌桶。以固定速率（QPS）往桶里放令牌，桶有容量上限。
 * 调用前先获取令牌，没令牌就阻塞等待。
 */
public final class RateLimiter {

    private final int qps;
    private final long intervalNanos;   // 每个令牌的间隔（纳秒）
    private final ReentrantLock lock = new ReentrantLock();

    private double availableTokens;     // 当前可用令牌（用 double 做平滑补充）
    private long lastRefillNanos;       // 上次补充令牌的时间

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
        this.qps = qps;
        this.intervalNanos = 1_000_000_000L / qps;
        this.availableTokens = qps;  // 启动时桶满
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
        lock.lock();
        try {
            while (true) {
                refill();
                if (availableTokens >= tokens) {
                    availableTokens -= tokens;
                    return;
                }
                // 令牌不够，计算需要等待的时间
                long needNanos = (long) ((tokens - availableTokens) * intervalNanos);
                availableTokens = 0;
                // 释放锁、睡眠、重试
                lock.unlock();
                sleepNanos(needNanos);
                lock.lock();
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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
     * @param tokens  需要的令牌数
     * @param timeout 超时时间
     * @return true 获取成功
     */
    public boolean tryAcquire(int tokens, Duration timeout) {
        if (tokens <= 0) return true;
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        lock.lock();
        try {
            while (true) {
                refill();
                if (availableTokens >= tokens) {
                    availableTokens -= tokens;
                    return true;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                long needNanos = (long) ((tokens - availableTokens) * intervalNanos);
                availableTokens = 0;
                long sleepNanos = Math.min(needNanos, remaining);
                lock.unlock();
                sleepNanos(sleepNanos);
                lock.lock();
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 按时间流逝补充令牌 */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            double newTokens = elapsed * 1.0 / intervalNanos;
            availableTokens = Math.min(availableTokens + newTokens, qps);  // 不超过桶容量
            lastRefillNanos = now;
        }
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
