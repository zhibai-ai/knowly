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

    /** 默认实体类型（跨领域通用；特定领域如中医/易卦通过构造器注入，不硬编码进源码） */
    private static final List<String> DEFAULT_TYPES = List.of("人物", "概念", "物品", "地点", "事件");

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
        return "你是一个知识图谱实体抽取助手。" +
                "从用户提供的文本中抽取实体，返回JSON格式。" +
                "实体类型包括：" + String.join("、", entityTypes) + "。" +
                "只抽取文本中明确出现的、有独立意义的实体，不要把普通名词、修饰语或动作当实体。" +
                "**每段文本最多抽取 15 个最重要的核心实体，宁缺毋滥。**" +
                "\n\n返回格式（严格JSON，不要加markdown代码块标记）：\n" +
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
