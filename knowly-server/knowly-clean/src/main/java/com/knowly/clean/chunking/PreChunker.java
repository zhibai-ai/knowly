package com.knowly.clean.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段一：按结构边界切 pre-chunk。
 *
 * <p>只管"在哪切"，不管切出来的多大。遍历文本行，遇到结构边界（标题行）就切一刀。
 *
 * <p><b>领域解耦</b>：标题识别规则分两类：
 * <ol>
 *   <li>通用规则（内置，core 无领域依赖）：Markdown {@code #} 标题、中文章节编号模式（第X章/X、/（一）/1.1）</li>
 *   <li>领域词表（外部注入，默认空）：如古籍的卦名/序名。由 {@code headingDictionary} 配置加载，
 *       不硬编码进源码——违反"通用不绑领域"原则。</li>
 * </ol>
 * 知了作为通用清洗工具，core 不带任何领域词。国学场景通过配置
 * {@code stages.ingest.heading_dictionary: headings-zh-classics.yaml} 启用卦名词表。
 */
public class PreChunker {

    /** Markdown 标题模式 */
    private static final Pattern MD_HEADING_PATTERN = Pattern.compile(
            "^#{1,6}\\s+.+", Pattern.MULTILINE);

    /** 中文章节标题模式（带编号） */
    private static final Pattern CN_NUMBERED_HEADING = Pattern.compile(
            "^第[一二三四五六七八九十百千零\\d]+[章节回卷篇条]"
            + "|^[一二三四五六七八九十]+[、.．]"
            + "|^[（(][一二三四五六七八九十\\d]+[)）]"
            + "|^\\d+[、.．]\\s*\\S"
            + "|^\\d+\\.\\d+\\s",                          // 1.1 / 2.3.1
            Pattern.MULTILINE);

    /** 默认通用无编号标题词表（仅含"序/前言/后记/目录"等跨领域通用的） */
    private static final Set<String> DEFAULT_UNNUMBERED_HEADINGS = Set.of(
            "序", "自序", "他序", "校堪序", "校勘序", "前言", "后记", "跋",
            "自 序", "他 序", "校 堪 序", "校 勘 序", "前 言", "后 记",
            "目录", "目 录", "目录:", "目 录:", "contents", "Contents", "CONTENTS",
            "引言", "导言", "导论", "绪论", "概述", "综述", "结论", "结语", "附录"
    );

    /** 目录条目模式：3 个以上连续 ASCII 点或省略号（后跟页码），如 "乾为天.......... 捌" */
    private static final Pattern TOC_ENTRY_PATTERN = Pattern.compile(
            "\\.{3,}|…{2,}|。{3,}");

    /** 目录标题（"目录/目 录/目錄/Contents"）——只切分不继承，防止后续正文被错标 */
    private static final Pattern TOC_TITLE_PATTERN = Pattern.compile(
            "(?i)^(目\\s*录|目\\s*錄|contents)$");

    /** 标题最大长度（超过的不算标题） */
    private static final int MAX_HEADING_LENGTH = 40;

    private final Set<String> unnumberedHeadings;

    /** 默认构造：仅用通用词表（领域无关） */
    public PreChunker() {
        this(DEFAULT_UNNUMBERED_HEADINGS);
    }

    /**
     * 自定义无编号标题词表。
     *
     * @param unnumberedHeadings 无编号标题词表（领域专用，如卦名）。可为 null 表示无。
     */
    public PreChunker(Set<String> unnumberedHeadings) {
        this.unnumberedHeadings = unnumberedHeadings == null ? Set.of() : unnumberedHeadings;
    }

    /**
     * 按结构边界切 pre-chunk。
     */
    public List<PreChunk> chunk(String content) {
        List<PreChunk> preChunks = new ArrayList<>();

        String[] lines = content.split("\n");
        StringBuilder currentBuffer = new StringBuilder();
        String currentSectionTitle = "";
        int sectionLevel = 0;

        for (String line : lines) {
            String trimmed = line.strip();
            int headingLevel = detectHeadingLevel(trimmed);

            if (headingLevel > 0) {
                // 遇到标题 → 先 flush 当前 buffer 为一个 pre-chunk
                if (!currentBuffer.isEmpty()) {
                    preChunks.add(new PreChunk(
                            currentBuffer.toString().strip(),
                            currentSectionTitle,
                            sectionLevel
                    ));
                    currentBuffer.setLength(0);
                }
                // "目录"特殊处理：只作切分边界，不更新章节标题。
                // 目录区之后的正文若迟迟遇不到下一个可识别标题（古籍正文标题格式不匹配），
                // 会一路挂着"目 录"——守一验收 P2：失眠内容被错标成"目 录"。
                // 目录标题对正文 chunk 无语义价值，宁空勿错。
                if (!TOC_TITLE_PATTERN.matcher(trimmed).matches()) {
                    currentSectionTitle = trimmed;
                    sectionLevel = headingLevel;
                }
            }
            currentBuffer.append(line).append("\n");
        }

        // flush 最后一个 buffer
        if (!currentBuffer.isEmpty()) {
            preChunks.add(new PreChunk(
                    currentBuffer.toString().strip(),
                    currentSectionTitle,
                    sectionLevel
            ));
        }

        return preChunks;
    }

    /**
     * 检测标题层级。返回 0 表示不是标题，1-6 表示层级。
     */
    private int detectHeadingLevel(String line) {
        if (line == null || line.isEmpty()) return 0;
        if (line.length() > MAX_HEADING_LENGTH) return 0;

        // 目录条目行：含引导点（....../……）+尾随页码 → 不是标题。
        // 典型形态："一、乾为天䷀.......... 捌"、"序 言........ 壹"。
        // 不排除的话，目录页每行都成"标题"，切出 N 个 23 字小 pre-chunk，
        // 触发碎片合并的病态路径（曾致地脉道目录区 42 个累积重复 chunk）。
        if (TOC_ENTRY_PATTERN.matcher(line).find()) return 0;

        // Markdown 标题
        if (MD_HEADING_PATTERN.matcher(line).matches()) {
            int count = 0;
            while (count < line.length() && line.charAt(count) == '#') count++;
            return count;
        }

        // 无编号标题（通用 + 领域词表）
        if (unnumberedHeadings.contains(line)) {
            return 2;
        }

        // 中文编号标题
        Matcher cnMatcher = CN_NUMBERED_HEADING.matcher(line);
        if (cnMatcher.find()) {
            if (line.matches("^第[一二三四五六七八九十百千零\\d]+章.*")) return 1;
            if (line.matches("^第[一二三四五六七八九十百千零\\d]+[节回卷篇条].*")) return 2;
            if (line.matches("^[一二三四五六七八九十]+[、.．].*")) return 2;
            if (line.matches("^[（(][一二三四五六七八九十\\d]+[)）].*")) return 2;
            if (line.matches("^\\d+[、.．].*")) return 3;
            if (line.matches("^\\d+\\.\\d+.*")) return 4;
            return 2;
        }

        return 0;
    }

    /** pre-chunk（结构边界内的文本块） */
    public record PreChunk(String text, String sectionTitle, int sectionLevel) {}
}
