package com.knowly.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SecretMasker} 单元测试。覆盖各模式脱敏、短值处理、配置 Map 包装。
 */
class SecretMaskerTest {

    @Test
    void should_mask_sk_prefix_api_key() {
        String masked = SecretMasker.mask("sk-abcd1234efgh5678");

        assertThat(masked).startsWith("sk-abc");
        assertThat(masked).contains("...");
        assertThat(masked).doesNotContain("5678gh");
    }

    @Test
    void should_mask_aws_access_key() {
        String masked = SecretMasker.mask("AKIAIOSFODNN7EXAMPLE");

        assertThat(masked).contains("...");
        assertThat(masked).doesNotContain("EXAMPLE");
    }

    @Test
    void should_mask_github_token() {
        String masked = SecretMasker.mask("ghp_abcdefghijklmnopqrstuvwxyz123456");

        assertThat(masked).contains("...");
    }

    @Test
    void should_mask_password_assignment() {
        String masked = SecretMasker.mask("password=knowly_dev");

        assertThat(masked).doesNotContain("knowly_dev");
        assertThat(masked).startsWith("password=");
    }

    @Test
    void should_mask_chinese_key_name() {
        String masked = SecretMasker.mask("密钥: sk-mysecret123456");

        assertThat(masked).doesNotContain("mysecret");
    }

    @Test
    void should_return_stars_when_value_too_short() {
        String masked = SecretMasker.maskValue("abc");

        assertThat(masked).isEqualTo("****");
    }

    @Test
    void should_not_touch_non_secret_text() {
        String text = "这是一段普通文本，不含任何密钥。";

        String masked = SecretMasker.mask(text);

        assertThat(masked).isEqualTo(text);
    }

    @Test
    void should_mask_secret_values_in_config_map() {
        Map<String, String> config = Map.of(
                "dashscope_api_key", "sk-1234567890abcdef",
                "normal_setting", "value");

        Map<String, String> masked = SecretMasker.wrap(config);

        assertThat(masked.get("dashscope_api_key")).contains("...");
        assertThat(masked.get("normal_setting")).isEqualTo("value");
    }

    @Test
    void should_handle_null_and_empty() {
        assertThat(SecretMasker.mask(null)).isNull();
        assertThat(SecretMasker.mask("")).isEmpty();
    }
}
