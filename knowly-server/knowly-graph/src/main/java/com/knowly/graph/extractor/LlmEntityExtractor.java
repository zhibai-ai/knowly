package com.knowly.graph.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowly.common.util.HashUtils;
import com.knowly.common.util.TextNormalizer;
import com.knowly.core.model.Entity;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.EntityExtractor;
import com.knowly.graph.llm.DashScopeLlmProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 LLM 的实体抽取器。
 *
 * <p>从每个 TextChunk 用 LLM 抽取实体（方剂/穴位/概念/人物...，类型可配置）。
 * 内置实体消歧（规范化后合并别名）。
 */
public class LlmEntityExtractor implements EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmEntityExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DashScopeLlmProvider llm;
    private final List<String> entityTypes;

    /** 默认实体类型（通用国学/中医场景）。砍掉"概念"——边界模糊，会把任意名词都塞进来。 */
    private static final List<String> DEFAULT_TYPES = List.of("人物", "卦象", "典籍", "地理", "官职", "术数");

    /** 实体类型的严格定义。LLM 必须据此判断，避免把描述性短语当实体。 */
    private static final String TYPE_DEFINITIONS =
            "各类型严格定义：\n" +
            "- 人物：真实历史人物（如 周文王、孔子、倪海厦）。代词/称谓不算（如 圣人、大人、海厦兄 不是人物）。\n" +
            "- 卦象：易经六十四卦名（如 乾、坤、屯、既济）或具体爻位（如 九五、上六）。卦辞/爻辞原文不算（如 亢龙有悔 是乾卦的辞，不是独立实体）。\n" +
            "- 典籍：有正式书名的著作（如 周易、易经、黄帝内经）。\n" +
            "- 地理：具体地名（如 长安、台湾）。\n" +
            "- 官职：古代官职/制度名（如 丞相、御史大夫）。\n" +
            "- 术数：山医命相卜等具体术数方法（如 六爻、风水、奇门遁甲）。\n" +
            "\n反例（以下【不】算实体，禁止抽取）：\n" +
            "- 段落描述语（如 穷之灾、广大包容、阴阳始交）\n" +
            "- 成语/引文（如 同声相应、云从龙风从虎）\n" +
            "- 普通名词（如 龙、月、大人、君德）\n" +
            "- 抽象概念词（如 中正之德、人道、天道）";

    public LlmEntityExtractor(DashScopeLlmProvider llm) {
        this(llm, DEFAULT_TYPES);
    }

    public LlmEntityExtractor(DashScopeLlmProvider llm, List<String> entityTypes) {
        this.llm = llm;
        this.entityTypes = entityTypes;
    }

    @Override
    public List<Entity> extract(TextChunk chunk) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(chunk);

        String response = llm.chatWithSystem(systemPrompt, userPrompt);

        // 解析 LLM 返回的 JSON
        List<Entity> entities = parseEntities(response, chunk);

        // 实体消歧：规范化后合并
        Map<String, Entity> deduped = new HashMap<>();
        for (Entity entity : entities) {
            String normalizedKey = TextNormalizer.normalize(entity.name());
            if (deduped.containsKey(normalizedKey)) {
                // 合并别名
                Entity existing = deduped.get(normalizedKey);
                List<String> aliases = new ArrayList<>(existing.aliases());
                if (!aliases.contains(entity.name())) {
                    aliases.add(entity.name());
                }
                deduped.put(normalizedKey, new Entity(
                        existing.id(), existing.name(), existing.type(),
                        normalizedKey, aliases
                ));
            } else {
                String id = "ent-" + HashUtils.contentHash(normalizedKey).substring(0, 12);
                deduped.put(normalizedKey, new Entity(id, entity.name(), entity.type(), normalizedKey, entity.aliases()));
            }
        }

        log.debug("实体抽取: chunk={}, 抽取 {} 个实体", chunk.id(), deduped.size());
        return new ArrayList<>(deduped.values());
    }

    private String buildSystemPrompt() {
        return "你是一个知识图谱实体抽取助手，正在对**学术研究用国学/中医古籍文本**进行结构化分析。" +
                "输入文本来自正式出版的学术与文化研究著作（如易经研究、中医典籍、命理学研究等），" +
                "其中出现的命理、卦象、医学、哲学名词属于客观的传统文化知识描述，应如实抽取，" +
                "用于构建学术研究用的知识图谱。本任务为客观知识结构化，不涉及价值判断。\n\n" +
                TYPE_DEFINITIONS + "\n\n" +
                "抽取要求：\n" +
                "1. 只抽取符合上述定义的实体，宁可漏抽不可滥抽。\n" +
                "2. 不要把卦辞、爻辞、引文、成语、描述性短语当实体。\n" +
                "3. 每个实体必须能独立成为一个知识图谱节点，有明确的指代对象。\n" +
                "4. 如果一段文本没有任何符合定义的实体，返回空数组。\n\n" +
                "返回格式（严格JSON，不要加markdown代码块标记）：\n" +
                "{\"entities\": [{\"name\": \"实体名\", \"type\": \"类型\", \"aliases\": [\"别名1\"]}]}";
    }

    private String buildUserPrompt(TextChunk chunk) {
        return "请从以下文本中抽取实体：\n\n" + chunk.text();
    }

    @SuppressWarnings("unchecked")
    private List<Entity> parseEntities(String response, TextChunk chunk) {
        try {
            // 清理可能的 markdown 代码块标记
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "");
            }
            Map<String, Object> result = MAPPER.readValue(json, Map.class);
            List<Map<String, Object>> entityList = (List<Map<String, Object>>) result.get("entities");
            if (entityList == null) return List.of();

            List<Entity> entities = new ArrayList<>();
            for (Map<String, Object> e : entityList) {
                String name = (String) e.get("name");
                String type = (String) e.get("type");
                List<String> aliases = (List<String>) e.getOrDefault("aliases", List.of());
                if (name == null || name.isBlank()) continue;
                entities.add(new Entity(null, name, type, null, aliases != null ? aliases : List.of()));
            }
            return entities;
        } catch (Exception e) {
            log.warn("实体 JSON 解析失败: chunk={}, cause={}, response={}", chunk.id(), e.getMessage(),
                    response.length() > 100 ? response.substring(0, 100) : response);
            return List.of();
        }
    }
}
