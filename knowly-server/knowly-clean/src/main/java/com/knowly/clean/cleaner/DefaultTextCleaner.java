package com.knowly.clean.cleaner;

import com.knowly.common.util.TextNormalizer;
import com.knowly.core.model.RawDocument;
import com.knowly.core.spi.TextCleaner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 默认文本清洗器（责任链模式）。
 *
 * <p>串联多个清洗步骤，每个步骤职责单一、可独立测试、可增删：
 * <ol>
 *   <li>去水印/特殊字符</li>
 *   <li>去页眉页脚标记（OCR 产生的噪声行）</li>
 *   <li>全角转半角 + 繁简统一 + 去多余空白（TextNormalizer）</li>
 * </ol>
 *
 * <p>去噪阶段的页眉页脚移除依赖版面分析的标记结果（LayoutAnalyzer 标记的 HEADER/FOOTER）。
 * v0.1 简化处理：用正则去除常见的页眉页脚模式。
 */
public class DefaultTextCleaner implements TextCleaner {

    /** 页码模式（如"第 12 页"、"Page 12"、"- 12 -"、"12/15"，以及 PDF 分页页脚的孤立数字行——
     *  深检发现 13% chunk 的句中嵌着页码："对你来\n32\n说"被切断了句子）。
     *  尾部 \\n? 连行尾换行一起删，避免留下空行碎片。 */
    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile(
            "(?m)^\\s*(第\\s*\\d+\\s*页|Page\\s*\\d+|-\\s*\\d+\\s*-|\\d+\\s*/\\s*\\d+|\\d{1,4})\\s*\\r?\\n?",
            Pattern.CASE_INSENSITIVE
    );

    /** 打印水印行（Adobe 打印页脚）：如"列印日期/时间：02/04/2005 23:20:53""列印页数：110"及简体变体。
     *  内容随页变化，行级频率统计打不中，必须模式匹配。 */
    private static final Pattern PRINT_MARK_PATTERN = Pattern.compile(
            "^(列印|打印)\\s*(日期|页数|頁數|时间|時間)(\\s*/\\s*(时间|時間))?\\s*[：:].*$"
    );

    /** 下载站水印行（来源站整行广告）：知盈/豆丁/道客巴巴等 */
    private static final Pattern DOWNLOAD_MARK_PATTERN = Pattern.compile(
            "(知盈医学|豆丁网|道客巴巴|百度文库|万部医学书籍|下载器所生成|更多资料|本资料来自)"
    );

    /** 整理者水印行（民间整理者页眉）：QQ号 / 整理与配图署名 / "第N页共M页"（守一P2） */
    private static final Pattern CURATOR_MARK_PATTERN = Pattern.compile(
            "(QQ[：:]?\\s*\\d{5,12}|整理与配图|第\\s*\\d+\\s*页\\s*共\\s*\\d+\\s*页)"
    );

    /** 连续横线/星号分隔线（常见水印/装饰） */
    private static final Pattern SEPARATOR_LINE_PATTERN = Pattern.compile(
            "(?m)^\\s*[-=*~_]{5,}\\s*$"
    );

    /** 零宽字符（BOM/不可见字符） */
    private static final Pattern ZERO_WIDTH_PATTERN = Pattern.compile(
            "[\u200B\u200C\u200D\uFEFF]"
    );

    /** 连续 3 个以上相同标点（OCR 重复噪声，如 。。。。） */
    private static final Pattern REPEATED_PUNCT_PATTERN = Pattern.compile(
            "([。！？，；：、])\\1{2,}"
    );

    private final List<CleanStep> steps;

    public DefaultTextCleaner() {
        this.steps = buildDefaultSteps();
    }

    /**
     * 自定义清洗步骤。
     *
     * @param steps 清洗步骤列表（按顺序执行）
     */
    public DefaultTextCleaner(List<CleanStep> steps) {
        this.steps = new ArrayList<>(steps);
    }

    @Override
    public String clean(String rawText, RawDocument source) {
        String result = rawText;
        for (CleanStep step : steps) {
            result = step.apply(result, source);
        }
        return result;
    }

    /** 构建默认清洗步骤链 */
    private List<CleanStep> buildDefaultSteps() {
        List<CleanStep> chain = new ArrayList<>();
        chain.add(this::removeZeroWidthChars);
        chain.add(this::removePageNumbers);
        chain.add(this::removeSeparatorLines);
        chain.add(this::reduceRepeatedPunctuation);
        // 先繁简转换（统一文字后，后续去重/去水印才能准确命中跨页重复）
        chain.add(this::normalize);
        // 先去重复段落（块级，精准）：版权声明/版式说明等多次出现的多行段落
        // 必须在水印检测前——否则水印的行级频率统计会把正常重复段落当水印误伤
        chain.add(this::removeRepeatedParagraphs);
        // 再去水印（行级）：此时版权块已处理，剩下的高频短行才是真水印
        chain.add(this::removeWatermarkLines);
        // 最后去打印水印（模式级）：Adobe 打印页脚"列印日期/时间/页数"每页内容变化，
        // 频率统计打不中，必须正则（守一验收 P1：曾致 19.5% chunk 被污染）
        chain.add(this::removePrintMarks);
        return chain;
    }

    // ────────────────────────────────────────────────────────────
    // 清洗步骤（每个是独立的 CleanStep）
    // ────────────────────────────────────────────────────────────

    /** 去零宽字符 */
    private String removeZeroWidthChars(String text, RawDocument source) {
        return ZERO_WIDTH_PATTERN.matcher(text).replaceAll("");
    }

    /** 去页码行 */
    private String removePageNumbers(String text, RawDocument source) {
        return PAGE_NUMBER_PATTERN.matcher(text).replaceAll("");
    }

    /** 去打印水印行：Adobe 打印页脚 + 下载站整行水印 + 整理者水印 */
    private String removePrintMarks(String text, RawDocument source) {
        return java.util.Arrays.stream(text.split("\n", -1))
                .filter(line -> !PRINT_MARK_PATTERN.matcher(line.strip()).matches())
                .filter(line -> !DOWNLOAD_MARK_PATTERN.matcher(line).find())
                .filter(line -> !CURATOR_MARK_PATTERN.matcher(line).find())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 去分隔线（连续横线/星号） */
    private String removeSeparatorLines(String text, RawDocument source) {
        return SEPARATOR_LINE_PATTERN.matcher(text).replaceAll("");
    }

    /** 压缩重复标点（OCR 常见噪声：。。。。 → 。） */
    private String reduceRepeatedPunctuation(String text, RawDocument source) {
        return REPEATED_PUNCT_PATTERN.matcher(text).replaceAll("$1");
    }

    /**
     * 去水印重复行/重复段。
     *
     * <p>PDF 水印的两大特征：
     * <ol>
     *   <li>页眉/页脚水印：同一段文本在多页重复出现（跨页高频）</li>
     *   <li>装饰性重复：同一字/词在一行内连续重复（如"校堪堪堪堪序序序序"）</li>
     * </ol>
     *
     * <p>策略：
     * <ul>
     *   <li>第一步：统计每个非空短行（≤30字）的出现频率，频率≥3次的视为水印行</li>
     *   <li>第二步：如果一行由水印子串拼接而成（如"倪海厦《人间道》"由高频"倪海厦"+"人间道"组成），也视为水印</li>
     *   <li>第三步：逐行处理——删水印 + 字符级去重</li>
     * </ul>
     */
    private String removeWatermarkLines(String text, RawDocument source) {
        String[] lines = text.split("\n", -1);

        // 先对每行做字符级去重（压缩"倪海厦倪海厦倪海厦"→"倪海厦"）
        // 这样水印行变短后才能被频率统计命中
        List<String> dedupedLines = new ArrayList<>();
        for (String line : lines) {
            dedupedLines.add(dedupRepeatedChars(line.strip()));
        }

        // 第一步：统计去重后的行频率，识别高频水印行。
        // 阈值 5：页眉/页脚水印通常每页出现，5 页以上的文档才稳定命中；
        // 提高阈值避免把正常重复短句（如章节小结、术语）误判为水印。
        Map<String, Integer> lineFreq = new HashMap<>();
        for (String line : dedupedLines) {
            if (!line.isEmpty() && line.length() <= 30) {
                lineFreq.merge(line, 1, Integer::sum);
            }
        }
        Set<String> watermarkLines = new HashSet<>();
        for (var entry : lineFreq.entrySet()) {
            if (entry.getValue() >= 5) {
                watermarkLines.add(entry.getKey());
            }
        }

        // 第二步：逐行处理——删水印 + 去连续相同行
        List<String> result = new ArrayList<>();
        for (String line : dedupedLines) {
            // 直接匹配水印 → 删
            if (watermarkLines.contains(line)) {
                continue;
            }
            // 检查是否由水印子串拼接而成（去掉水印子串后只剩标点/空）
            if (!line.isEmpty() && line.length() <= 30) {
                String remaining = line;
                for (String part : watermarkLines) {
                    if (part.length() >= 2) {
                        remaining = remaining.replace(part, "");
                    }
                }
                if (remaining.isBlank()) {
                    continue;
                }
            }
            // 连续相同行去重
            if (!result.isEmpty() && line.equals(result.get(result.size() - 1))) {
                continue;
            }
            if (!line.isEmpty()) {
                result.add(line);
            }
        }

        return String.join("\n", result);
    }

    /**
     * 字符级去重。
     * 处理 PDF 装饰水印产生的字符重复：
     * "校堪堪堪堪序序序序" → "校堪序"
     * "》》》》" → "》"
     * "人間道人間道人間道" → "人間道"
     */
    private String dedupRepeatedChars(String line) {
        if (line.length() < 4) return line;

        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            // 尝试找最长的重复单元
            boolean foundRepeat = false;
            // 单元长度从大到小试（优先匹配长单元）
            for (int unitLen = Math.min(line.length() / 3, 8); unitLen >= 1; unitLen--) {
                if (i + unitLen > line.length()) continue;
                String unit = line.substring(i, i + unitLen);
                // 跳过空白单元
                if (unit.isBlank()) continue;

                // 统计连续重复次数
                int count = 1;
                int pos = i + unitLen;
                while (pos + unitLen <= line.length() && line.substring(pos, pos + unitLen).equals(unit)) {
                    count++;
                    pos += unitLen;
                }

                if (count >= 3) {
                    // 压缩为1次
                    sb.append(unit);
                    i = pos;
                    foundRepeat = true;
                    break;
                }
            }
            if (!foundRepeat) {
                sb.append(line.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /** 规范化（全角转半角 + 繁简统一 + 去多余空白） */
    private String normalize(String text, RawDocument source) {
        return TextNormalizer.normalize(text);
    }

    /**
     * 去重复段落（多行长文本块在文档中出现 ≥3 次 → 只保留首次）。
     *
     * <p>典型场景：版权声明、版式说明等，跨页重复出现但不是单行水印。
     * 用"连续3行文本"的指纹做频率统计。
     */
    private String removeRepeatedParagraphs(String text, RawDocument source) {
        String[] lines = text.split("\n");
        if (lines.length < 6) return text;

        // 统计"连续3行"文本块的指纹频率
        Map<String, Integer> blockFreq = new HashMap<>();
        for (int i = 0; i + 2 < lines.length; i++) {
            if (lines[i].strip().isEmpty()) continue;
            String block = (lines[i].strip() + "\n" + lines[i + 1].strip() + "\n" + lines[i + 2].strip());
            if (block.length() < 30) continue;
            blockFreq.merge(block, 1, Integer::sum);
        }

        // 频率 ≥3 的块视为重复段落
        Set<String> repeatedBlocks = new HashSet<>();
        for (var entry : blockFreq.entrySet()) {
            if (entry.getValue() >= 3) {
                repeatedBlocks.add(entry.getKey());
            }
        }
        if (repeatedBlocks.isEmpty()) return text;

        // 逐行扫描，遇到重复块时跳过（只保留首次出现）
        Set<String> seenBlocks = new HashSet<>();
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            if (i + 2 < lines.length && !lines[i].strip().isEmpty()) {
                String block = (lines[i].strip() + "\n" + lines[i + 1].strip() + "\n" + lines[i + 2].strip());
                if (repeatedBlocks.contains(block)) {
                    if (seenBlocks.contains(block)) {
                        // 已经有了，跳过这3行
                        i += 3;
                        continue;
                    } else {
                        // 首次出现，保留并标记
                        seenBlocks.add(block);
                    }
                }
            }
            result.add(lines[i]);
            i++;
        }

        return String.join("\n", result);
    }

    /**
     * 清洗步骤接口。每个步骤是独立的纯函数。
     * <p>便于测试和组合——测试时可以单独测某一步，跳过其他步骤。
     */
    @FunctionalInterface
    public interface CleanStep {
        String apply(String text, RawDocument source);
    }
}
