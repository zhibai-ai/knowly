package com.knowly.cli.command;

import com.knowly.graph.GraphBuilder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * graph 命令——从清洗产出的 JSONL 构建知识图谱。
 *
 * <p>用法：knowly graph --input ./output/01-chunks/chunks.jsonl
 *
 * <p>需要环境变量：
 * - DASHSCOPE_API_KEY（LLM 抽取用）
 * - NEO4J_URI / NEO4J_USER / NEO4J_PASSWORD（图谱存储用，有默认值）
 */
public class GraphCommand {

    public void execute(String[] args) throws Exception {
        String inputPath = null;
        String entityTypesArg = null;
        String relationTypesArg = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> inputPath = args[++i];
                case "--entity-types" -> entityTypesArg = args[++i];
                case "--relation-types" -> relationTypesArg = args[++i];
                case "--help", "-h" -> {
                    System.out.println("""
                            用法: knowly graph --input <chunks.jsonl 路径>

                            从清洗产出的 JSONL 构建知识图谱（实体 + 关系 → Neo4j）。

                            选项:
                              --input <file>      JSONL 文件路径（必需）
                              --entity-types <t>  实体类型，逗号分隔（默认通用：人物,概念,物品,地点,事件）
                              --relation-types <t> 关系类型，逗号分隔（默认通用：相关,组成,包含,...）
                              --neo4j-uri         Neo4j 连接地址（默认 bolt://localhost:7687）
                              --neo4j-user        Neo4j 用户名（默认 neo4j）
                              --neo4j-pass        Neo4j 密码（默认 knowly_dev）

                            环境变量:
                              DASHSCOPE_API_KEY  百炼 API Key（必需）

                            易卦场景示例:
                              --entity-types 卦象,象征物,意象,人物,概念,方位,吉凶
                              --relation-types 象征,预示,代表,对应,包含,组成,相关,吉凶
                            """);
                    return;
                }
            }
        }

        if (inputPath == null) {
            System.err.println("错误：必须指定 --input <JSONL 文件路径>");
            System.err.println("示例：knowly graph --input ./output/01-chunks/chunks.jsonl");
            System.exit(1);
        }

        // API Key
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：未配置 DASHSCOPE_API_KEY 环境变量");
            System.exit(1);
        }

        // Neo4j 配置（有默认值）
        String neo4jUri = System.getenv().getOrDefault("NEO4J_URI", "bolt://localhost:7687");
        String neo4jUser = System.getenv().getOrDefault("NEO4J_USER", "neo4j");
        String neo4jPass = System.getenv().getOrDefault("NEO4J_PASSWORD", "knowly_dev");

        // 实体/关系类型（自定义领域类型，逗号分隔；null 用默认通用类型）
        List<String> entityTypes = entityTypesArg != null
                ? Arrays.asList(entityTypesArg.split(",")) : null;
        List<String> relationTypes = relationTypesArg != null
                ? Arrays.asList(relationTypesArg.split(",")) : null;

        System.out.println("开始构建知识图谱...");
        System.out.println("  输入: " + inputPath);
        System.out.println("  Neo4j: " + neo4jUri);
        if (entityTypes != null) System.out.println("  实体类型: " + entityTypes);
        if (relationTypes != null) System.out.println("  关系类型: " + relationTypes);
        System.out.println();

        GraphBuilder builder = new GraphBuilder(apiKey, neo4jUri, neo4jUser, neo4jPass, entityTypes, relationTypes);
        var stats = builder.build(Path.of(inputPath));

        System.out.println();
        System.out.println("════════════════════════════════════");
        System.out.println("  图谱构建完成！");
        System.out.println("  处理 chunk: " + stats.totalChunks());
        System.out.println("  实体总数:   " + stats.totalEntities());
        System.out.println("  关系总数:   " + stats.totalRelations());
        System.out.println("  失败 chunk: " + stats.failedChunks());
        System.out.println("════════════════════════════════════");
    }
}
