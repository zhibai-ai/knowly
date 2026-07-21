package com.knowly.graph.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowly.common.util.HashUtils;
import com.knowly.core.model.Entity;
import com.knowly.core.model.Relation;
import com.knowly.core.model.TextChunk;
import com.knowly.core.spi.RelationExtractor;
import com.knowly.graph.llm.DashScopeLlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 LLM 的关系抽取器。
 *
 * <p>基于已抽取的实体列表，用 LLM 从文本中抽取实体间关系。
 */
public class LlmRelationExtractor implements RelationExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmRelationExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DashScopeLlmProvider llm;
    private final List<String> relationTypes;

    /** 默认关系类型 */
    private static final List<String> DEFAULT_TYPES = List.of("组成", "主治", "相关", "生克", "归属", "属于", "包含", "位于");

    public LlmRelationExtractor(DashScopeLlmProvider llm) {
        this(llm, DEFAULT_TYPES);
    }

    public LlmRelationExtractor(DashScopeLlmProvider llm, List<String> relationTypes) {
        this.llm = llm;
        this.relationTypes = relationTypes;
    }

    @Override
    public List<Relation> extract(TextChunk chunk, List<Entity> entities) {
        if (entities == null || entities.isEmpty()) return List.of();

        String systemPrompt = buildSystemPrompt(entities);
        String userPrompt = buildUserPrompt(chunk);

        String response = llm.chatWithSystem(systemPrompt, userPrompt);
        List<Relation> relations = parseRelations(response, chunk, entities);

        log.debug("关系抽取: chunk={}, 抽取 {} 个关系", chunk.id(), relations.size());
        return relations;
    }

    private String buildSystemPrompt(List<Entity> entities) {
        StringBuilder entityList = new StringBuilder();
        for (Entity e : entities) {
            entityList.append("- ").append(e.name());
            if (e.type() != null) entityList.append(" (").append(e.type()).append(")");
            entityList.append("\n");
        }
        return "你是一个知识图谱关系抽取助手。" +
                "从用户提供的文本中，针对以下已知实体，抽取它们之间的关系。\n" +
                "已知实体：\n" + entityList +
                "\n关系类型包括：" + String.join("、", relationTypes) + "。" +
                "只抽取文本中明确体现的关系，不要猜测。\n" +
                "source 和 target 必须是已知实体列表中的名称。\n\n" +
                "返回格式（严格JSON，不要加markdown代码块标记）：\n" +
                "{\"relations\": [{\"source\": \"实体A名\", \"target\": \"实体B名\", \"type\": \"关系类型\", \"confidence\": 0.9}]}";
    }

    private String buildUserPrompt(TextChunk chunk) {
        return "请从以下文本中抽取实体间的关系：\n\n" + chunk.text();
    }

    @SuppressWarnings("unchecked")
    private List<Relation> parseRelations(String response, TextChunk chunk, List<Entity> entities) {
        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "");
            }
            Map<String, Object> result = MAPPER.readValue(json, Map.class);
            List<Map<String, Object>> relList = (List<Map<String, Object>>) result.get("relations");
            if (relList == null) return List.of();

            // 建立实体名 → Entity 的查找表
            Map<String, Entity> entityMap = new java.util.HashMap<>();
            for (Entity e : entities) {
                entityMap.put(e.name(), e);
                if (e.aliases() != null) {
                    for (String alias : e.aliases()) {
                        entityMap.put(alias, e);
                    }
                }
            }

            List<Relation> relations = new ArrayList<>();
            for (Map<String, Object> r : relList) {
                String sourceName = (String) r.get("source");
                String targetName = (String) r.get("target");
                String type = (String) r.get("type");
                double confidence = r.get("confidence") instanceof Number n ? n.doubleValue() : 0.8;

                Entity sourceEntity = entityMap.get(sourceName);
                Entity targetEntity = entityMap.get(targetName);
                if (sourceEntity == null || targetEntity == null) continue;

                String relId = "rel-" + HashUtils.contentHash(
                        sourceEntity.id() + targetEntity.id() + type).substring(0, 12);
                relations.add(new Relation(relId, sourceEntity.id(), targetEntity.id(),
                        type, chunk.id(), confidence));
            }
            return relations;
        } catch (Exception e) {
            log.warn("关系 JSON 解析失败: chunk={}, cause={}", chunk.id(), e.getMessage());
            return List.of();
        }
    }
}
