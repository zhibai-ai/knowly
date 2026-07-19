package com.knowly.cli;

import com.knowly.cli.command.InitCommand;
import com.knowly.cli.command.ReportCommand;
import com.knowly.cli.command.RunCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知了 CLI 入口。
 *
 * <p>命令格式：knowly &lt;command&gt; [options]
 * <ul>
 *   <li>{@code knowly run --config pipeline.yaml --input ./docs --output ./kb} — 运行清洗</li>
 *   <li>{@code knowly init} — 生成模板配置</li>
 *   <li>{@code knowly report ./kb} — 查看报告</li>
 *   <li>{@code knowly help} — 帮助</li>
 * </ul>
 */
public class KnowlyMain {

    private static final Logger log = LoggerFactory.getLogger(KnowlyMain.class);

    public static void main(String[] args) {
        if (args.length == 0) {
            printHelp();
            System.exit(0);
        }

        String command = args[0];
        String[] remainingArgs = new String[args.length - 1];
        System.arraycopy(args, 1, remainingArgs, 0, remainingArgs.length);

        try {
            switch (command) {
                case "run" -> new RunCommand().execute(remainingArgs);
                case "init" -> new InitCommand().execute(remainingArgs);
                case "report" -> new ReportCommand().execute(remainingArgs);
                case "help", "--help", "-h" -> printHelp();
                case "version", "--version", "-v" -> printVersion();
                default -> {
                    System.err.println("未知命令: " + command);
                    printHelp();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            log.error("执行失败: {}", e.getMessage(), e);
            System.err.println("错误: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("""
                知了 knowly — 开源的数据 AI 化清洗工具

                用法:
                  knowly <command> [options]

                命令:
                  run      运行清洗流水线
                           knowly run --config pipeline.yaml --input ./docs --output ./kb
                  init     生成模板配置文件
                           knowly init [--template <名称>]
                  report   查看处理报告
                           knowly report ./kb
                  help     显示帮助
                  version  显示版本

                选项:
                  --config <file>     配置文件路径
                  --template <name>   使用配置模板（替代 --config）
                  --input <dir>       输入目录（覆盖配置文件中的值）
                  --output <dir>      输出目录（覆盖配置文件中的值）
                  --stage <stage>     只运行指定阶段（ingest/clean/embed），用于调试
                """);
    }

    private static void printVersion() {
        System.out.println("知了 knowly v0.1.0-SNAPSHOT");
    }
}
