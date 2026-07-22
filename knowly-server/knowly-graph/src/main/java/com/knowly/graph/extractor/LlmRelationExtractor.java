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

    /**
     * 默认关系类型。砍掉"相关"——它是兜底垃圾关系，LLM 会把任意共现都塞进来。
     * 每个类型都有明确语义方向，强制 LLM 只能从枚举中选。
     */
    private static final List<String> DEFAULT_TYPES = List.of(
            "组成",     // A 由 B 组成（如：六十四卦 由 乾卦 组成）
            "主治",     // A 主治 B（如：桂枝汤 主治 外感风寒）
            "生克",     // A 生/克 B（如：水生木、水克火）
            "归属",     // A 归属于 B（如：九五 归属 乾卦）
            "属于",     // A 是 B 的一种（如：乾为天 属于 六十四卦）
            "包含",     // A 包含 B（如：易经 包含 六十四卦）
            "位于",     // A 位于 B（如：长安 位于 关中）
            "注释",     // A 注释/解读 B（如：程颐 注释 周易）
            "师承",     // A 师承/学习于 B（如：倪海厦 师承 桐君）
            "引用"      // A 引用 B（如：人间道 引用 周易）
    );

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
        return "你是一个知识图谱关系抽取助手，正在对**学术研究用国学/中医古籍文本**进行结构化分析。" +
                "输入文本来自正式出版的学术与文化研究著作（如易经研究、中医典籍、命理学研究等），" +
                "其中出现的命理、卦象、医学、哲学名词属于客观的传统文化知识描述。" +
                "本任务为客观知识结构化，不涉及价值判断。\n\n" +
                "从用户提供的文本中，针对以下已知实体，抽取它们之间的关系。\n" +
                "已知实体：\n" + entityList +
                "\n关系类型【必须且只能】从以下枚举中选择（禁止生造关系类型）：\n" +
                String.join("、", relationTypes) + "\n" +
                "\n抽取要求：\n" +
                "1. 只抽取文本中明确体现的关系。如果文本中没有明确关系，返回 {\"relations\": []}。\n" +
                "2. source 和 target 必须是已知实体列表中的名称（精确匹配）。\n" +
                "3. 宁可少抽不可滥抽——没有明确语义关系的共现不抽取。\n" +
                "4. confidence 仅在关系明确时给 0.9+，模糊关系给 0.6-0.8，存疑则不抽。\n\n" +
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

                // 兜底 1：关系类型必须在白名单内（prompt 约束不住 LLM 生造，代码强制过滤）
                if (type == null || !relationTypes.contains(type)) {
                    log.debug("丢弃非白名单关系类型: type={}, source={}, target={}", type, sourceName, targetName);
                    continue;
                }
                // 兜底 2：confidence 低于 0.6 的存疑关系丢弃
                if (confidence < 0.6) {
                    log.debug("丢弃低置信度关系: conf={}, source={}, target={}", confidence, sourceName, targetName);
                    continue;
                }

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
