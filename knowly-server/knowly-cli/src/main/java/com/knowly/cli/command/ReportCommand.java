package com.knowly.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * report 命令——显示处理报告摘要。
 */
public class ReportCommand {

    public void execute(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: knowly report <产出目录>");
            System.exit(1);
        }

        Path outputDir = Path.of(args[0]);
        Path reportDir = outputDir.resolve("02-reports");

        // 处理报告
        Path processingReport = reportDir.resolve("processing-report.json");
        if (Files.exists(processingReport)) {
            System.out.println("═══ 处理报告 ═══");
            System.out.println(Files.readString(processingReport));
        } else {
            System.out.println("未找到处理报告（该目录可能未被知了处理过）");
        }

        // 错误报告
        Path errorReport = reportDir.resolve("error-report.json");
        if (Files.exists(errorReport)) {
            String content = Files.readString(errorReport);
            if (!content.trim().equals("{}") && !content.contains("\"failed\": 0")) {
                System.out.println("\n═══ 错误报告 ═══");
                System.out.println(content);
            }
        }
    }
}
