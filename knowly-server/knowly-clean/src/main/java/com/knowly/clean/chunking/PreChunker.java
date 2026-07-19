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

    /** 无编号中文标题词表（常见于古籍/教材） */
    private static final Set<String> CN_UNNUMBERED_HEADINGS = Set.of(
            // 序言类
            "序", "自序", "他序", "校堪序", "校勘序", "前言", "后记", "跋",
            "自 序", "他 序", "校 堪 序", "校 勘 序", "前 言", "后 记",
            // 目录类
            "目录", "目 录", "目录:", "目 录:", "contents", "Contents", "CONTENTS",
            // 通识标题
            "引言", "导言", "导论", "绪论", "概述", "综述", "结论", "结语", "附录",
            // 易经/古籍专用
            "乾为天", "坤为地", "水雷屯", "山水蒙", "水天需", "天水讼",
            "地水师", "水地比", "风天小畜", "天泽履", "地天泰", "天地否",
            "天火同人", "火天大有", "地山谦", "雷地豫", "泽雷随", "山风蛊",
            "地泽临", "风地观", "火雷噬嗑", "山火贲", "山地剥", "地雷复",
            "天雷无妄", "山天大畜", "山雷颐", "泽风大过", "坎为水", "离为火",
            "泽山咸", "雷风恒", "天山遯", "雷天大壮", "火地晋", "地火明夷",
            "风火家人", "火泽睽", "水山蹇", "雷水解", "山泽损", "风雷益",
            "泽天夬", "天风姤", "泽地萃", "地风升", "泽水困", "水风井",
            "泽火革", "火风鼎", "震为雷", "艮为山", "风山渐", "雷泽归妹",
            "雷火丰", "火山旅", "巽为风", "兑为泽", "风水涣", "水泽节",
            "风泽中孚", "雷山小过", "水火既济", "火水未济",
            "乾卦", "坤卦", "屯卦", "蒙卦", "需卦", "讼卦",
            "师卦", "比卦", "小畜", "履卦", "泰卦", "否卦",
            "同人", "大有", "谦卦", "豫卦", "随卦", "蛊卦",
            "临卦", "观卦", "噬嗑", "贲卦", "剥卦", "复卦",
            "无妄", "大畜", "颐卦", "大过", "坎卦", "离卦",
            "咸卦", "恒卦", "遁卦", "大壮", "晋卦", "明夷",
            "家人", "睽卦", "蹇卦", "解卦", "损卦", "益卦",
            "夬卦", "姤卦", "萃卦", "升卦", "困卦", "井卦",
            "革卦", "鼎卦", "震卦", "艮卦", "渐卦", "归妹",
            "丰卦", "旅卦", "巽卦", "兑卦", "涣卦", "节卦",
            "中孚", "小过", "既济", "未济"
    );

    /** 标题最大长度（超过的不算标题） */
    private static final int MAX_HEADING_LENGTH = 40;

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
                // 更新当前章节标题
                currentSectionTitle = trimmed;
                sectionLevel = headingLevel;
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

        // Markdown 标题
        if (MD_HEADING_PATTERN.matcher(line).matches()) {
            int count = 0;
            while (count < line.length() && line.charAt(count) == '#') count++;
            return count;
        }

        // 无编号中文标题（校堪序/自序/前言/卦名等）
        if (CN_UNNUMBERED_HEADINGS.contains(line)) {
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
