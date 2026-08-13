package com.knowly.core.config;

import com.knowly.common.exception.ConfigException;
import com.knowly.common.exception.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 配置加载器——把 YAML 转成强类型 {@link PipelineConfig}。
 *
 * <p>三层覆盖合并（后者覆盖前者）：
 * <pre>
 *   classpath:knowly-defaults.yaml   ← 内置默认
 *        ↓ 覆盖
 *   --config pipeline.yaml           ← 用户配置
 *        ↓ 覆盖
 *   --key value 命令行参数            ← 最高优先级
 * </pre>
 *
 * <p>关键职责：
 * <ol>
 *   <li>YAML SafeConstructor 反序列化（防任意类反序列化攻击）</li>
 *   <li>{@code ${VAR}} / {@code ${VAR:default}} 环境变量解析（含密钥，绝不硬编码进源码）</li>
 *   <li>必填项 + 依赖关系校验（如配了向量库 sink 但无 API key → 明确报错）</li>
 *   <li>转成不可变强类型对象</li>
 * </ol>
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    /** ${VAR} 或 ${VAR:default} 匹配模式 */
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\\}");

    private ConfigLoader() {}

    /**
     * 加载并合并配置。
     *
     * @param configPath    用户配置文件路径（可为 null：仅用默认值 + 命令行参数）
     * @param cliOverrides  命令行参数覆盖（如 input/output）。key 用点分路径如 "pipeline.input"
     * @return 强类型配置
     */
    public static PipelineConfig load(Path configPath, Map<String, String> cliOverrides) {
        Map<String, Object> merged = new LinkedHashMap<>();

        // 1) 默认配置
        Map<String, Object> defaults = loadDefaults();
        deepMerge(merged, defaults);

        // 2) 用户配置
        if (configPath != null) {
            Map<String, Object> user = loadYamlFile(configPath);
            deepMerge(merged, user);
        }

        // 3) 命令行覆盖
        applyCliOverrides(merged, cliOverrides);

        // 4) 环境变量解析（${VAR} 替换）
        resolveEnvVars(merged);

        // 5) 校验
        validate(merged);

        log.info("配置加载完成: name={}, sinks={}", nameOf(merged), sinkTypes(merged));
        return toStronglyTyped(merged);
    }

    /** 便捷重载：无命令行覆盖 */
    public static PipelineConfig load(Path configPath) {
        return load(configPath, Map.of());
    }

    // ────────────────────────────────────────────────────────────
    // 加载
    // ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadDefaults() {
        try (var is = ConfigLoader.class.getResourceAsStream("/knowly-defaults.yaml")) {
            if (is == null) {
                log.warn("未找到内置默认配置 knowly-defaults.yaml，仅使用用户配置");
                return new LinkedHashMap<>();
            }
            Yaml yaml = newYaml();
            Object loaded = yaml.load(is);
            return loaded == null ? new LinkedHashMap<>() : (Map<String, Object>) loaded;
        } catch (Exception e) {
            throw new ConfigException(ErrorCode.CONFIG_001,
                    "加载内置默认配置失败", "knowly-defaults.yaml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYamlFile(Path path) {
        if (!Files.exists(path)) {
            throw new ConfigException(ErrorCode.CONFIG_002,
                    "配置文件不存在", "path=" + path);
        }
        try (var is = Files.newInputStream(path)) {
            Yaml yaml = newYaml();
            Object loaded = yaml.load(is);
            return loaded == null ? new LinkedHashMap<>() : (Map<String, Object>) loaded;
        } catch (Exception e) {
            throw new ConfigException(ErrorCode.CONFIG_001,
                    "配置文件解析失败", "path=" + path, e);
        }
    }

    /** SafeConstructor 防反序列化攻击 */
    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    // ────────────────────────────────────────────────────────────
    // 深度合并（后者覆盖前者）
    // ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (var entry : source.entrySet()) {
            String key = entry.getKey();
            Object srcVal = entry.getValue();
            Object tgtVal = target.get(key);
            if (tgtVal instanceof Map && srcVal instanceof Map) {
                // 两边都是 Map → 递归合并
                Map<String, Object> mergedChild = new LinkedHashMap<>((Map<String, Object>) tgtVal);
                deepMerge(mergedChild, (Map<String, Object>) srcVal);
                target.put(key, mergedChild);
            } else if (srcVal instanceof List) {
                // 列表整体替换（sinks 不做元素级合并）
                target.put(key, new ArrayList<>((List<Object>) srcVal));
            } else {
                target.put(key, srcVal);
            }
        }
    }

    /** 应用命令行覆盖（如 pipeline.input） */
    @SuppressWarnings("unchecked")
    private static void applyCliOverrides(Map<String, Object> config, Map<String, String> overrides) {
        for (var entry : overrides.entrySet()) {
            String[] keys = entry.getKey().split("\\.");
            Map<String, Object> current = config;
            for (int i = 0; i < keys.length - 1; i++) {
                Object next = current.get(keys[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<>();
                    current.put(keys[i], next);
                }
                current = (Map<String, Object>) next;
            }
            current.put(keys[keys.length - 1], entry.getValue());
        }
    }

    // ────────────────────────────────────────────────────────────
    // 环境变量解析
    // ────────────────────────────────────────────────────────────

    /** 递归解析所有字符串值里的 ${VAR} / ${VAR:default} */
    @SuppressWarnings("unchecked")
    static void resolveEnvVars(Map<String, Object> config) {
        for (var entry : config.entrySet()) {
            entry.setValue(resolveValue(entry.getValue()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolveValue(Object value) {
        if (value instanceof String s) {
            return resolveString(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                resolved.put(e.getKey().toString(), resolveValue(e.getValue()));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveValue(item));
            }
            return resolved;
        }
        return value;
    }

    static String resolveString(String s) {
        Matcher matcher = ENV_PATTERN.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultVal = matcher.group(2);
            String envVal = System.getenv(varName);
            String replacement;
            if (envVal != null && !envVal.isEmpty()) {
                replacement = envVal;
            } else if (defaultVal != null) {
                replacement = defaultVal;  // ${VAR:default} 的 default 分支
            } else {
                // 无默认值且环境变量缺失 → 保留占位符，由校验阶段判断是否必填
                // 不在此抛错：非密钥的 ${VAR} 可能可选（如 ${KNOWLY_HOME}）
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    // ────────────────────────────────────────────────────────────
    // 校验
    // ────────────────────────────────────────────────────────────

    static void validate(Map<String, Object> config) {
        // pipeline.name 必填
        if (nameOf(config) == null || nameOf(config).isBlank()) {
            throw new ConfigException(ErrorCode.CONFIG_002,
                    "缺少必填配置项: pipeline.name",
                    "请在配置文件顶层 pipeline.name 填写流水线名称");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> pipeline = (Map<String, Object>) config.getOrDefault("pipeline", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> stages = (Map<String, Object>) pipeline.getOrDefault("stages", Map.of());
        PipelineConfig.ChunkingConfig chunking = extractChunking((Map<String, Object>) stages.get("clean"));
        if (chunking != null) {
            if (chunking.maxSize() <= 0) {
                throw new ConfigException(ErrorCode.CONFIG_004,
                        "clean.chunking.max_size 必须 > 0", "actual=" + chunking.maxSize());
            }
            if (chunking.overlap() < 0 || chunking.overlap() >= chunking.maxSize()) {
                throw new ConfigException(ErrorCode.CONFIG_004,
                        "clean.chunking.overlap 必须 ∈ [0, max_size)",
                        "overlap=" + chunking.overlap() + ", max_size=" + chunking.maxSize());
            }
            if (chunking.minSize() < 0) {
                throw new ConfigException(ErrorCode.CONFIG_004,
                        "clean.chunking.min_size 必须 >= 0", "actual=" + chunking.minSize());
            }
        }

        // 依赖关系：配了向量库 sink 但无 embedding 配置 → 报错
        List<String> sinkTypes = sinkTypes(config);
        boolean needsEmbed = sinkTypes.stream().anyMatch(
                t -> t.equals("pgvector") || t.equals("qdrant"));
        if (needsEmbed) {
            String apiKey = embedApiKey(config);
            if (apiKey == null || apiKey.isBlank() || apiKey.contains("${")) {
                throw new ConfigException(ErrorCode.CONFIG_003,
                        "配置了向量库 sink 但未提供 DashScope API Key",
                        "请设置环境变量 DASHSCOPE_API_KEY，或在配置 stages.embed.api_key 显式指定");
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // 转 strong typed
    // ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static PipelineConfig toStronglyTyped(Map<String, Object> config) {
        Map<String, Object> pipeline = (Map<String, Object>) config.getOrDefault("pipeline", Map.of());

        return new PipelineConfig(
                nameOf(config),
                str(pipeline, "input"),
                str(pipeline, "output"),
                extractState(pipeline),
                extractStages(pipeline),
                extractSinks(pipeline),
                extractErrorHandling(pipeline),
                extractConcurrency(pipeline)
        );
    }

    @SuppressWarnings("unchecked")
    private static String nameOf(Map<String, Object> config) {
        Map<String, Object> pipeline = (Map<String, Object>) config.get("pipeline");
        return pipeline == null ? null : str(pipeline, "name");
    }

    @SuppressWarnings("unchecked")
    private static List<String> sinkTypes(Map<String, Object> config) {
        return extractSinks((Map<String, Object>) config.getOrDefault("pipeline", Map.of()))
                .stream().map(SinkConfig::type).toList();
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.StateConfig extractState(Map<String, Object> pipeline) {
        Map<String, Object> state = (Map<String, Object>) pipeline.getOrDefault("state", Map.of());
        String path = str(state, "path");
        return new PipelineConfig.StateConfig(
                str(state, "store", "sqlite"),
                path == null ? null : Path.of(path));
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.StagesConfig extractStages(Map<String, Object> pipeline) {
        Map<String, Object> stages = (Map<String, Object>) pipeline.getOrDefault("stages", Map.of());
        return new PipelineConfig.StagesConfig(
                extractIngest((Map<String, Object>) stages.get("ingest")),
                extractClean((Map<String, Object>) stages.get("clean")),
                extractEmbed((Map<String, Object>) stages.get("embed"))
        );
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.IngestStage extractIngest(Map<String, Object> ingest) {
        if (ingest == null) ingest = Map.of();
        Map<String, Object> ocr = (Map<String, Object>) ingest.getOrDefault("ocr", Map.of());
        return new PipelineConfig.IngestStage(
                strList(ingest, "parsers", List.of("pdf", "doc", "docx", "image", "generic")),
                str(ingest, "layout_analysis", "off"),
                new PipelineConfig.OcrConfig(
                        bool(ocr, "enabled", false),
                        strList(ocr, "languages", List.of("chi_sim", "eng")),
                        intVal(ocr, "min_chars_per_page", 10)),
                str(ingest, "heading_dictionary", null)
        );
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.CleanStage extractClean(Map<String, Object> clean) {
        if (clean == null) clean = Map.of();
        Map<String, Object> normalize = (Map<String, Object>) clean.getOrDefault("normalize", Map.of());
        Map<String, Object> dedup = (Map<String, Object>) clean.getOrDefault("dedup", Map.of());
        return new PipelineConfig.CleanStage(
                str(clean, "encoding", "auto"),
                new PipelineConfig.NormalizeConfig(
                        bool(normalize, "fullwidth_to_halfwidth", true),
                        bool(normalize, "traditional_to_simplified", true),
                        bool(normalize, "trim_whitespace", true)),
                extractDedup(dedup),
                extractChunking(clean)
        );
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.DedupConfig extractDedup(Map<String, Object> dedup) {
        Map<String, Object> fileLevel = (Map<String, Object>) dedup.getOrDefault("file_level", Map.of());
        Map<String, Object> paraLevel = (Map<String, Object>) dedup.getOrDefault("paragraph_level", Map.of());
        return new PipelineConfig.DedupConfig(
                new PipelineConfig.FileLevelDedup(
                        bool(fileLevel, "enabled", true),
                        str(fileLevel, "method", "hash")),
                new PipelineConfig.ParagraphLevelDedup(
                        bool(paraLevel, "enabled", true),
                        str(paraLevel, "method", "minhash"),
                        doubleVal(paraLevel, "threshold", 0.85))
        );
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.ChunkingConfig extractChunking(Map<String, Object> clean) {
        if (clean == null) return null;
        Map<String, Object> chunking = (Map<String, Object>) clean.getOrDefault("chunking", Map.of());
        return new PipelineConfig.ChunkingConfig(
                str(chunking, "strategy", "structure_aware"),
                intVal(chunking, "max_size", 500),
                intVal(chunking, "overlap", 50),
                intVal(chunking, "min_size", 50));
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.EmbedStage extractEmbed(Map<String, Object> embed) {
        if (embed == null) return null;
        return new PipelineConfig.EmbedStage(
                str(embed, "provider", "dashscope"),
                str(embed, "model", "text-embedding-v3"),
                intVal(embed, "dimensions", 1024),
                str(embed, "api_key"),
                intVal(embed, "batch_size", 25),
                intVal(embed, "qps_limit", 10));
    }

    @SuppressWarnings("unchecked")
    private static String embedApiKey(Map<String, Object> config) {
        Map<String, Object> pipeline = (Map<String, Object>) config.getOrDefault("pipeline", Map.of());
        Map<String, Object> stages = (Map<String, Object>) pipeline.getOrDefault("stages", Map.of());
        Map<String, Object> embed = (Map<String, Object>) stages.get("embed");
        return embed == null ? null : str(embed, "api_key");
    }

    @SuppressWarnings("unchecked")
    private static List<SinkConfig> extractSinks(Map<String, Object> pipeline) {
        Object sinksVal = pipeline.get("sinks");
        List<SinkConfig> result = new ArrayList<>();
        if (sinksVal instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Object typeVal = m.get("type");
                    String type = typeVal == null ? "" : typeVal.toString();
                    Map<String, Object> props = new LinkedHashMap<>();
                    for (var e : m.entrySet()) {
                        if (!"type".equals(e.getKey())) {
                            props.put(e.getKey().toString(), e.getValue());
                        }
                    }
                    result.add(new SinkConfig(type, props));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.ErrorHandlingConfig extractErrorHandling(Map<String, Object> pipeline) {
        Map<String, Object> eh = (Map<String, Object>) pipeline.getOrDefault("error_handling", Map.of());
        Map<String, Object> retry = (Map<String, Object>) eh.getOrDefault("retry", Map.of());
        return new PipelineConfig.ErrorHandlingConfig(
                str(eh, "on_file_failure", "SKIP_AND_LOG"),
                new PipelineConfig.RetryConfig(
                        intVal(retry, "max", 3),
                        str(retry, "backoff", "exponential"),
                        bool(retry, "jitter", true)));
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig.ConcurrencyConfig extractConcurrency(Map<String, Object> pipeline) {
        Map<String, Object> conc = (Map<String, Object>) pipeline.getOrDefault("concurrency", Map.of());
        return new PipelineConfig.ConcurrencyConfig(
                intVal(conc, "ingest_threads", 4),
                intVal(conc, "clean_threads", 2),
                intVal(conc, "embed_threads", 2),
                intVal(conc, "sink_threads", 2),
                intVal(conc, "queue_capacity", 100));
    }

    // ────────────────────────────────────────────────────────────
    // 类型转换辅助
    // ────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static String str(Map<String, Object> map, String key, String defaultValue) {
        String v = str(map, key);
        return v == null ? defaultValue : v;
    }

    private static int intVal(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static double doubleVal(Map<String, Object> map, String key, double defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean bool(Map<String, Object> map, String key, boolean defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> map, String key, List<String> defaultValue) {
        Object v = map.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return defaultValue;
    }
}
