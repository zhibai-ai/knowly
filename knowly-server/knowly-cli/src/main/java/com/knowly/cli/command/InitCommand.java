package com.knowly.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * init 命令——在当前目录生成 pipeline.yaml 模板配置。
 */
public class InitCommand {

    public void execute(String[] args) throws Exception {
        String templateName = null;
        for (int i = 0; i < args.length; i++) {
            if ("--template".equals(args[i]) && i + 1 < args.length) {
                templateName = args[++i];
            }
        }

        String content;
        if (templateName != null) {
            // 从模板目录加载
            String templatesDir = System.getenv().getOrDefault("KNOWLY_HOME",
                    System.getProperty("user.home") + "/.knowly") + "/templates";
            Path templatePath = Path.of(templatesDir, templateName + ".yaml");
            content = Files.readString(templatePath);
        } else {
            // 默认模板（clean-only：纯文件产出）
            content = DEFAULT_PIPELINE_YAML;
        }

        Path outputPath = Path.of("pipeline.yaml");
        Files.writeString(outputPath, content);
        System.out.println("已生成配置文件: " + outputPath.toAbsolutePath());
        System.out.println("编辑后运行: knowly run --config pipeline.yaml --input ./docs --output ./kb");
    }

    private static final String DEFAULT_PIPELINE_YAML = """
            # 知了 knowly 流水线配置
            pipeline:
              # 输入输出目录（可被 --input/--output 覆盖）
              input: ./docs
              output: ./kb

              # 清洗参数
              clean:
                normalize:
                  fullwidth_to_halfwidth: true
                  traditional_to_simplified: true
                  trim_whitespace: true
                dedup:
                  file_level:
                    enabled: true
                  paragraph_level:
                    enabled: true
                    threshold: 0.85
                chunking:
                  strategy: structure_aware
                  max_size: 500
                  overlap: 50

              # 产出目标（不配则默认 Markdown + JSONL）
              # 取消注释启用向量库：
              # embed:
              #   provider: dashscope
              #   model: text-embedding-v3
              #   dimensions: 1024
              #   api_key: ${DASHSCOPE_API_KEY}
            """;
}
