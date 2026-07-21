package com.knowly.graph.llm;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.ParseException;
import com.knowly.common.util.RateLimiter;
import com.knowly.common.util.RetryTemplate;
import com.knowly.core.spi.LlmProvider;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DashScope LLM Provider（图谱层用）。
 *
 * <p>用通义千问模型做实体/关系抽取。支持 system + user 双 prompt。
 * 强制 JSON 输出（responseFormat=json_object），便于解析。
 */
public class DashScopeLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(DashScopeLlmProvider.class);

    private final String apiKey;
    private final String model;
    private final Generation generation;
    private final RateLimiter rateLimiter;
    private final RetryTemplate retryTemplate;

    /**
     * 默认用 qwen-plus（性价比最优）。
     */
    public DashScopeLlmProvider(String apiKey) {
        this(apiKey, "qwen-plus");
    }

    public DashScopeLlmProvider(String apiKey, String model) {
        this(apiKey, model, 5, 3);
    }

    public DashScopeLlmProvider(String apiKey, String model, int qpsLimit, int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ParseException(ErrorCode.EMBED_001, "DashScope API Key 未配置",
                    "图谱层需要 LLM API Key");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.generation = new Generation();
        this.rateLimiter = RateLimiter.create(qpsLimit);
        this.retryTemplate = RetryTemplate.builder()
                .maxAttempts(maxRetries)
                .exponentialBackoff(Duration.ofMillis(1000), 2.0, Duration.ofSeconds(30))
                .build();
        log.info("DashScopeLlmProvider 初始化: model={}, qps={}", model, qpsLimit);
    }

    @Override
    public String chat(String prompt) {
        return chatWithSystem(null, prompt);
    }

    /**
     * 带 system prompt 的对话。
     */
    public String chatWithSystem(String systemPrompt, String userPrompt) {
        rateLimiter.acquire();
        return retryTemplate.execute(() -> {
            try {
                var messages = new java.util.ArrayList<Message>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    messages.add(Message.builder().role("system").content(systemPrompt).build());
                }
                messages.add(Message.builder().role("user").content(userPrompt).build());

                GenerationParam param = GenerationParam.builder()
                        .apiKey(apiKey)
                        .model(model)
                        .messages(messages)
                        .resultFormat("message")
                        .build();

                GenerationResult result = generation.call(param);
                String text = result.getOutput().getChoices().get(0).getMessage().getContent();
                log.debug("LLM 响应: model={}, 响应长度={}", model, text.length());
                return text;
            } catch (Exception e) {
                throw new ParseException(ErrorCode.EMBED_001, "LLM 调用失败",
                        "model=" + model + ", cause=" + e.getMessage(), e);
            }
        });
    }
}
