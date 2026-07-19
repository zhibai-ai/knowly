package com.knowly.embed.dashscope;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.knowly.common.exception.EmbedException;
import com.knowly.common.exception.ErrorCode;
import com.knowly.common.util.RateLimiter;
import com.knowly.common.util.RetryTemplate;
import com.knowly.core.spi.EmbeddingProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DashScope Embedding 提供者（默认实现）。
 *
 * <p>基于阿里官方 dashscope-sdk-java。
 * 固化：text-embedding-v3 / 1024 维。
 * 限流：RateLimiter 令牌桶（默认 10 QPS）。
 * 重试：RetryTemplate 指数退避+抖动（默认 3 次）。
 */
public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingProvider.class);

    /** 固化的模型名和维度 */
    private static final String MODEL = "text-embedding-v3";
    private static final int DIMENSIONS = 1024;

    /** 批量调用的最大文本数（DashScope 单次最多 25 条） */
    private static final int BATCH_SIZE = 10;

    private final String apiKey;
    private final RateLimiter rateLimiter;
    private final RetryTemplate retryTemplate;
    private final TextEmbedding textEmbedding;

    /**
     * 创建 DashScope embedding 提供者（默认 QPS=10, 重试 3 次）。
     *
     * @param apiKey DashScope API Key
     */
    public DashScopeEmbeddingProvider(String apiKey) {
        this(apiKey, 10, 3);
    }

    /**
     * 创建 DashScope embedding 提供者。
     *
     * @param apiKey     DashScope API Key
     * @param qpsLimit   QPS 限制
     * @param maxRetries 最大重试次数
     */
    public DashScopeEmbeddingProvider(String apiKey, int qpsLimit, int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbedException(ErrorCode.EMBED_001, "DashScope API Key 未配置",
                    "请设置环境变量 DASHSCOPE_API_KEY 或在设置页配置");
        }
        this.apiKey = apiKey;
        this.textEmbedding = new TextEmbedding();
        this.rateLimiter = RateLimiter.create(qpsLimit);
        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(maxRetries)
                .exponentialBackoff(Duration.ofMillis(500), 2.0, Duration.ofSeconds(10))
                .build();
        log.info("DashScopeEmbeddingProvider 初始化: model={}, dims={}, qps={}", MODEL, DIMENSIONS, qpsLimit);
    }

    @Override
    public float[] embed(String text) {
        rateLimiter.acquire();
        return retryTemplate.execute(() -> {
            try {
                TextEmbeddingParam param = TextEmbeddingParam.builder()
                        .apiKey(apiKey)
                        .model(MODEL)
                        .dimension(DIMENSIONS)
                        .texts(List.of(text))
                        .build();
                TextEmbeddingResult result = textEmbedding.call(param);
                List<TextEmbeddingResultItem> items = result.getOutput().getEmbeddings();
                float[] vector = toFloatArray(items.get(0).getEmbedding());
                validateDimension(vector);
                return vector;
            } catch (EmbedException e) {
                throw e;
            } catch (Exception e) {
                throw new EmbedException(ErrorCode.EMBED_001, "Embedding 调用失败",
                        "textLen=" + text.length() + ", cause=" + e.getMessage(), e);
            }
        });
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        List<float[]> allResults = new ArrayList<>(texts.size());

        // 分批调用（每批最多 BATCH_SIZE 条）
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, texts.size());
            List<String> batch = texts.subList(i, end);

            rateLimiter.acquire();
            List<float[]> batchResults = retryTemplate.execute(() -> {
                try {
                    TextEmbeddingParam param = TextEmbeddingParam.builder()
                            .apiKey(apiKey)
                            .model(MODEL)
                            .dimension(DIMENSIONS)
                            .texts(batch)
                            .build();
                    TextEmbeddingResult result = textEmbedding.call(param);
                    List<TextEmbeddingResultItem> items = result.getOutput().getEmbeddings();
                    List<float[]> results = new ArrayList<>(items.size());
                    for (TextEmbeddingResultItem item : items) {
                        float[] vector = toFloatArray(item.getEmbedding());
                        validateDimension(vector);
                        results.add(vector);
                    }
                    return results;
                } catch (EmbedException e) {
                    throw e;
                } catch (Exception e) {
                    throw new EmbedException(ErrorCode.EMBED_001, "Embedding 批量调用失败",
                            "batchSize=" + batch.size() + ", cause=" + e.getMessage(), e);
                }
            });
            allResults.addAll(batchResults);
        }
        return allResults;
    }

    @Override
    public int dimension() {
        return DIMENSIONS;
    }

    /** List<Double> → float[] */
    private static float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }

    /** 校验返回的向量维度 */
    private void validateDimension(float[] vector) {
        if (vector.length != DIMENSIONS) {
            throw new EmbedException(ErrorCode.EMBED_003, "Embedding 维度不匹配",
                    "expected=" + DIMENSIONS + ", actual=" + vector.length);
        }
    }
}
