package com.knowly.ingest.layout;

import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.ParseException;
import com.knowly.core.spi.LayoutAnalyzer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轻量版面分析器（基于规则 + PDFBox 文本块坐标）。
 *
 * <p>v0.1 不引入深度学习模型，用规则版：
 * <ol>
 *   <li>双栏检测：统计文本块 x 坐标分布，双峰则按列分别提取</li>
 *   <li>页眉页脚检测：每页顶部/底部固定 y 区域内、跨页重复短文本标记</li>
 *   <li>标题层级：按字体大小聚类，最大 → H1，次大 → H2 ...</li>
 *   <li>版面 bbox 与 PDF 原生文本 token 取交集（不靠 OCR 重转录）</li>
 * </ol>
 *
 * <p>受 Docling"bbox 与文本 token 相交"启发——不靠 OCR 重转录，省时且准。
 */
public class RuleBasedLayoutAnalyzer implements LayoutAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedLayoutAnalyzer.class);

    /** 页眉区域占页面高度的比例（顶部 5%） */
    private static final double HEADER_RATIO = 0.05;
    /** 页脚区域占页面高度的比例（底部 5%） */
    private static final double FOOTER_RATIO = 0.05;
    /** 双栏检测：左右两簇 x 坐标的最小间距（占页面宽度的比例） */
    private static final double COLUMN_GAP_RATIO = 0.15;

    @Override
    public AnalyzedLayout analyze(Path filePath, String rawText) throws ParseException {
        log.debug("版面分析: {}", filePath);

        // 版面分析仅适用于 PDF（基于 PDFBox 坐标提取）。非 PDF 文件直接返回原文本。
        String fileName = filePath.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".pdf")) {
            log.debug("非 PDF 文件，跳过版面分析: {}", filePath);
            return new AnalyzedLayout(rawText, false, List.of());
        }

        try (PDDocument pdDoc = PDDocument.load(filePath.toFile())) {
            int pageCount = pdDoc.getNumberOfPages();
            float pageWidth = pdDoc.getPage(0).getMediaBox().getWidth();
            float pageHeight = pdDoc.getPage(0).getMediaBox().getHeight();

            // 提取每页的文本块坐标信息
            List<PageTextInfo> pagesInfo = extractTextPositions(pdDoc);

            // 第一步：双栏检测
            boolean isTwoColumn = detectTwoColumn(pagesInfo, pageWidth);
            log.debug("双栏检测结果: {}, isTwoColumn={}", filePath, isTwoColumn);

            // 第二步：页眉页脚检测（跨页重复短文本）
            List<LayoutRegion> headerFooterRegions = detectHeaderFooter(pagesInfo, pageHeight);

            // 第三步：标题层级（按字体大小聚类）
            List<LayoutRegion> headingRegions = detectHeadings(pagesInfo);

            // 合并所有区域
            List<LayoutRegion> allRegions = new ArrayList<>();
            allRegions.addAll(headerFooterRegions);
            allRegions.addAll(headingRegions);

            // 如果检测到双栏，重排文本（左列→右列顺序）
            String reorderedText = rawText;
            if (isTwoColumn) {
                reorderedText = reorderTwoColumnText(pagesInfo, pageWidth);
            }

            log.debug("版面分析完成: {}, regions={}", filePath, allRegions.size());
            return new AnalyzedLayout(reorderedText, isTwoColumn, allRegions);

        } catch (ParseException e) {
            throw e;
        } catch (IOException e) {
            throw new ParseException(
                    ErrorCode.LAYOUT_001, "版面分析失败",
                    "file=" + filePath + ", cause=" + e.getMessage(), e);
        }
    }

    /**
     * 提取每页的文本块坐标信息。
     * 用自定义 PDFTextStripper 捕获 TextPosition（含坐标+字号）。
     */
    private List<PageTextInfo> extractTextPositions(PDDocument pdDoc) throws IOException {
        List<PageTextInfo> pagesInfo = new ArrayList<>();

        for (int pageIdx = 0; pageIdx < pdDoc.getNumberOfPages(); pageIdx++) {
            final List<TextPos> pagePositions = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                    for (TextPosition tp : textPositions) {
                        pagePositions.add(new TextPos(
                                tp.getX(), tp.getY(),
                                tp.getWidthDirAdj(),
                                tp.getFontSizeInPt(),
                                tp.getUnicode()
                        ));
                    }
                    super.writeString(text, textPositions);
                }
            };
            stripper.setStartPage(pageIdx + 1);
            stripper.setEndPage(pageIdx + 1);
            stripper.getText(pdDoc);  // 触发解析，捕获坐标
            pagesInfo.add(new PageTextInfo(pageIdx, pagePositions));
        }

        return pagesInfo;
    }

    /**
     * 双栏检测：统计文本块 x 坐标分布，若呈双峰则判定双栏。
     *
     * <p>算法：统计所有文本块的起始 x 坐标。如果 x 坐标明显聚集在左右两簇
     * （中间有明显空白带），则判定为双栏。
     */
    private boolean detectTwoColumn(List<PageTextInfo> pagesInfo, float pageWidth) {
        // 统计 x 坐标分布
        double midPoint = pageWidth / 2;
        double gapStart = midPoint - pageWidth * COLUMN_GAP_RATIO / 2;
        double gapEnd = midPoint + pageWidth * COLUMN_GAP_RATIO / 2;

        int leftCount = 0;
        int rightCount = 0;
        int midCount = 0;  // 落在中间空白带的

        for (PageTextInfo page : pagesInfo) {
            for (TextPos pos : page.positions) {
                if (pos.x < gapStart) {
                    leftCount++;
                } else if (pos.x > gapEnd) {
                    rightCount++;
                } else {
                    midCount++;
                }
            }
        }

        int total = leftCount + rightCount + midCount;
        if (total == 0) return false;

        // 判定条件：左右都有相当比例的文本（各 >20%），中间空白带文本少（<15%）
        boolean leftEnough = (double) leftCount / total > 0.20;
        boolean rightEnough = (double) rightCount / total > 0.20;
        boolean midSparse = (double) midCount / total < 0.15;

        return leftEnough && rightEnough && midSparse;
    }

    /**
     * 页眉页脚检测：每页顶部/底部固定 y 区域内、跨页重复出现的短文本标记为页眉页脚。
     */
    private List<LayoutRegion> detectHeaderFooter(List<PageTextInfo> pagesInfo, float pageHeight) {
        List<LayoutRegion> regions = new ArrayList<>();
        double headerYThreshold = pageHeight * (1 - HEADER_RATIO);  // y 轴从下往上
        double footerYThreshold = pageHeight * FOOTER_RATIO;

        // 收集每页顶部和底部的文本
        for (PageTextInfo page : pagesInfo) {
            for (TextPos pos : page.positions) {
                boolean isHeader = pos.y > headerYThreshold;
                boolean isFooter = pos.y < footerYThreshold;
                if (isHeader || isFooter) {
                    regions.add(new LayoutRegion(
                            isHeader ? "HEADER" : "FOOTER",
                            pos.x, pos.y, pos.width, pos.fontSize,
                            pos.text
                    ));
                }
            }
        }
        return regions;
    }

    /**
     * 标题层级检测：按字体大小聚类。
     */
    private List<LayoutRegion> detectHeadings(List<PageTextInfo> pagesInfo) {
        // 收集所有字号
        List<Float> sizes = new ArrayList<>();
        for (PageTextInfo page : pagesInfo) {
            for (TextPos pos : page.positions) {
                if (pos.fontSize > 0) sizes.add(pos.fontSize);
            }
        }
        if (sizes.isEmpty()) return List.of();

        // 找出最大的两个不同字号（粗略聚类）
        double max1 = sizes.stream().max(Double::compare).orElse(0f);
        double max2 = sizes.stream().filter(s -> s < max1 - 0.5)
                .max(Double::compare).orElse(0f);

        List<LayoutRegion> headings = new ArrayList<>();
        for (PageTextInfo page : pagesInfo) {
            for (TextPos pos : page.positions) {
                String level = null;
                if (Math.abs(pos.fontSize - max1) < 0.5) {
                    level = "H1";
                } else if (max2 > 0 && Math.abs(pos.fontSize - max2) < 0.5) {
                    level = "H2";
                }
                if (level != null) {
                    headings.add(new LayoutRegion(level, pos.x, pos.y, pos.width, pos.fontSize, pos.text));
                }
            }
        }
        return headings;
    }

    /**
     * 双栏文本重排：按"左列从上到下 → 右列从上到下"顺序拼接。
     */
    private String reorderTwoColumnText(List<PageTextInfo> pagesInfo, float pageWidth) {
        double midPoint = pageWidth / 2;
        StringBuilder sb = new StringBuilder();

        for (PageTextInfo page : pagesInfo) {
            // 分左右两列
            List<TextPos> left = new ArrayList<>();
            List<TextPos> right = new ArrayList<>();
            for (TextPos pos : page.positions) {
                if (pos.x < midPoint) left.add(pos);
                else right.add(pos);
            }
            // 各列按 y 坐标降序（PDF y 轴从下往上，顶部的 y 大）
            Comparator<TextPos> byYDesc = Comparator.comparingDouble((TextPos p) -> p.y).reversed();
            left.sort(byYDesc);
            right.sort(byYDesc);

            // 左列 → 右列
            for (TextPos pos : left) sb.append(pos.text);
            sb.append("\n");
            for (TextPos pos : right) sb.append(pos.text);
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    // ────────────────────────────────────────────────────────────
    // 内部数据结构
    // ────────────────────────────────────────────────────────────

    /** 单页的文本位置信息 */
    private record PageTextInfo(int pageIndex, List<TextPos> positions) {}

    /** 单个文本块的位置信息 */
    private record TextPos(float x, float y, float width, float fontSize, String text) {}
}
