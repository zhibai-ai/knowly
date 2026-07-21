package com.knowly.ingest.pdf;

import com.knowly.common.enums.ParseStatus;
import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.ParseException;
import com.knowly.common.util.HashUtils;
import com.knowly.common.util.TextNormalizer;
import com.knowly.core.model.DocumentMetadata;
import com.knowly.core.model.RawDocument;
import com.knowly.core.spi.DocumentParser;
import com.knowly.core.spi.OcrEngine;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PDF 专用解析器。
 *
 * <p>处理流程：
 * <ol>
 *   <li>用 PDFBox 提取文本层</li>
 *   <li>如果字符数/页数 < 阈值（默认 10），判定为扫描版 PDF</li>
 *   <li>扫描版：把 PDF 每页渲染成图片，调 OCR 引擎识别</li>
 *   <li>组装 RawDocument</li>
 * </ol>
 *
 * <p>优先级高于 {@link com.knowly.ingest.tika.TikaDocumentParser}，PDF 先由此解析器处理。
 */
public class PdfDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(PdfDocumentParser.class);

    /** 扫描版判定阈值：每页平均字符数低于此值则判定为扫描版 */
    private static final int MIN_CHARS_PER_PAGE = 10;

    /** 大页数扫描版 PDF 的 OCR 上限——超过此页数的扫描版 OCR 质量差且耗时极长，直接跳过 */
    private static final int MAX_OCR_PAGES = 50;

    /** 扫描版 OCR 时 PDF 渲染 DPI */
    private static final int OCR_RENDER_DPI = 300;

    private final OcrEngine ocrEngine;
    private final List<String> ocrLanguages;

    /**
     * 创建 PDF 解析器。
     *
     * @param ocrEngine    OCR 引擎（扫描版 PDF 时用）
     * @param ocrLanguages OCR 语言列表
     */
    public PdfDocumentParser(OcrEngine ocrEngine, List<String> ocrLanguages) {
        this.ocrEngine = ocrEngine;
        this.ocrLanguages = ocrLanguages;
    }

    @Override
    public boolean supports(String fileName, String mimeType) {
        if (fileName == null) return false;
        return fileName.toLowerCase().endsWith(".pdf");
    }

    @Override
    public int priority() {
        // 高于 Tika（兜底 0），PDF 优先由此解析器处理
        return 10;
    }

    @Override
    public RawDocument parse(Path filePath) throws ParseException {
        log.debug("PDF 解析: {}", filePath);

        try (PDDocument pdDoc = PDDocument.load(filePath.toFile())) {
            int pageCount = pdDoc.getNumberOfPages();

            // 第一步：提取文本层
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdDoc);
            int charCount = text.length();

            log.debug("PDF 文本提取: {}, pages={}, chars={}", filePath, pageCount, charCount);

            // 第二步：判断是否扫描版（需要 OCR）
            boolean isScanned = pageCount > 0 && (charCount / pageCount) < MIN_CHARS_PER_PAGE;

            // 第三步：如果是扫描版，进一步判断是否为图片型 PDF
            // 图片型 PDF（如卦图、漫画）：每页有大面积图片，且整体文字极少
            // 对这种 PDF 做 OCR 只会产生噪声——应该只保留文字层，不做 OCR
            boolean isImageBasedPdf = false;
            if (isScanned) {
                isImageBasedPdf = checkImageBasedPdf(pdDoc, pageCount, charCount);
                if (isImageBasedPdf) {
                    log.info("检测到图片型 PDF（图片为主，非扫描文字），跳过 OCR: {}, pages={}", filePath, pageCount);
                }
            }

            String content;
            ParseStatus status;
            java.util.List<com.knowly.core.model.DocumentImage> images = new java.util.ArrayList<>();

            if (isScanned && !isImageBasedPdf) {
                // 真正的扫描版文字 PDF → 做 OCR
                // 但如果页数太多（如 255 页竖版扫描），OCR 质量差且耗时极长（10-20分钟），直接跳过
                if (pageCount > MAX_OCR_PAGES) {
                    log.warn("扫描版 PDF 页数过多（{} 页 > {}），跳过 OCR 避免低质量产出: {}", pageCount, MAX_OCR_PAGES, filePath);
                    throw new ParseException(ErrorCode.PARSE_004,
                            "扫描版 PDF 页数过多，跳过 OCR",
                            "file=" + filePath + ", pages=" + pageCount + ", max=" + MAX_OCR_PAGES);
                }
                log.info("检测到扫描版 PDF，转 OCR: {}, pages={}", filePath, pageCount);
                content = ocrPdf(pdDoc, pageCount);
                status = content.isBlank() ? ParseStatus.FAILED : ParseStatus.SUCCESS;
            } else if (isImageBasedPdf) {
                // 图片型 PDF → 提取文字层 + 提取图片
                log.info("图片型 PDF，提取图片: {}, pages={}", filePath, pageCount);
                java.util.List<Object> extractResult = extractImages(pdDoc, pageCount, text);
                content = (String) extractResult.get(0);
                @SuppressWarnings("unchecked")
                java.util.List<com.knowly.core.model.DocumentImage> imgs =
                        (java.util.List<com.knowly.core.model.DocumentImage>) extractResult.get(1);
                images = imgs;
                status = ParseStatus.SUCCESS;
            } else {
                // 正常文字 PDF → 只提取文字层
                content = text.strip();
                status = ParseStatus.SUCCESS;
            }

            // 组装 RawDocument
            String normalized = TextNormalizer.normalize(content);
            String contentHash = HashUtils.contentHash(normalized);
            String documentId = HashUtils.documentId(contentHash);
            String fileName = filePath.getFileName().toString();

            DocumentMetadata metadata = new DocumentMetadata(
                    java.nio.file.Files.size(filePath),
                    pageCount,
                    pdDoc.getDocumentInformation() != null
                            ? pdDoc.getDocumentInformation().getAuthor() : null,
                    null,  // PDF 创建时间提取复杂，暂不填
                    "PdfDocumentParser",
                    buildExtra(pdDoc, isScanned)
            );

            return new RawDocument(
                    documentId,
                    filePath.toString(),
                    fileName,
                    "pdf",
                    content,
                    metadata,
                    contentHash,
                    status,
                    images
            );
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException(
                    ErrorCode.PARSE_002, "PDF 解析失败",
                    "file=" + filePath + ", cause=" + e.getMessage(), e);
        }
    }

    /**
     * 从图片型 PDF 中提取图片为独立文件，并在文本中插入图片位置标记。
     *
     * <p>图片保存到临时目录（由 MarkdownSink 后续复制到产出目录）。
     * 文本中插入 HTML 注释标记：{@code <!-- knowly:image page="1" file="images/xxx.png" alt="卦名" -->}
     *
     * @return List{0=带图片标记的文本, 1=DocumentImage列表}
     */
    private java.util.List<Object> extractImages(PDDocument pdDoc, int pageCount,
                                                  String originalText) throws IOException {
        java.util.List<com.knowly.core.model.DocumentImage> images = new java.util.ArrayList<>();
        StringBuilder content = new StringBuilder();

        // 创建临时图片目录
        Path tempImgDir = java.nio.file.Files.createTempDirectory("knowly-images-");
        Path imgDir = tempImgDir.resolve("images");
        java.nio.file.Files.createDirectories(imgDir);

        // 用 PDFRenderer 渲染整页为图片（正确处理旋转/变换矩阵，避免倒立）
        PDFRenderer renderer = new PDFRenderer(pdDoc);

        for (int pageIdx = 0; pageIdx < pageCount; pageIdx++) {
            // 检查该页是否有图片
            var page = pdDoc.getPage(pageIdx);
            var resources = page.getResources();
            boolean hasImage = false;
            if (resources != null) {
                for (var name : resources.getXObjectNames()) {
                    try {
                        var xObject = resources.getXObject(name);
                        if (xObject instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img) {
                            if (img.getWidth() >= 50 && img.getHeight() >= 50) {
                                hasImage = true;
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (!hasImage) continue;  // 没有图片的页面跳过

            // 渲染整页为图片（150 DPI，方向正确）
            String imgFileName = String.format("p%d.png", pageIdx + 1);
            Path imgPath = imgDir.resolve(imgFileName);

            try {
                BufferedImage pageImage = renderer.renderImageWithDPI(pageIdx, 150);
                javax.imageio.ImageIO.write(pageImage, "png", imgPath.toFile());
            } catch (Exception renderEx) {
                log.warn("页面 {} 渲染失败（可能含不支持的 JPEG 编码），跳过: {}", pageIdx + 1, renderEx.getMessage());
                continue;  // 跳过该页，继续下一页
            }

            // 从文本中找该页的标题作为 alt 文字
            String altText = findPageTitle(originalText, pageIdx);

            images.add(new com.knowly.core.model.DocumentImage(
                    pageIdx + 1,
                    "images/" + imgFileName,
                    altText
            ));

            log.debug("渲染页面图片: page={}, file={}, alt={}", pageIdx + 1, imgFileName, altText);
        }

        // 构建带图片标记的文本
        String[] textLines = originalText.split("\n");
        int currentImageIdx = 0;

        for (String line : textLines) {
            String trimmed = line.strip();
            content.append(trimmed).append("\n");

            // 如果这一行匹配某个图片的 altText，在后面插入图片标记
            if (currentImageIdx < images.size()) {
                var img = images.get(currentImageIdx);
                if (img.altText() != null && !img.altText().isEmpty()
                        && trimmed.equals(img.altText())) {
                    content.append(String.format(
                            "<!-- knowly:image page=\"%d\" file=\"%s\" alt=\"%s\" -->\n",
                            img.page(), img.filePath(), img.altText()));
                    currentImageIdx++;
                }
            }
        }

        return java.util.List.of(content.toString().strip(), images);
    }

    /** 从文本中找到指定页码对应的标题（启发式：取该页位置的第一个短行） */
    private String findPageTitle(String text, int pageIdx) {
        // 简化：按页分割文本（PDF 文本提取时每页之间有分隔）
        // 对于卦图这种文档，每页只有一个短词（如"乾卦"）
        // 直接从 text 里按顺序找第 pageIdx+1 个短行（≤5字）
        String[] lines = text.split("\n");
        int shortLineCount = 0;
        for (String line : lines) {
            String s = line.strip();
            if (!s.isEmpty() && s.length() <= 5 && s.matches(".*\\p{Script=Han}.*")) {
                shortLineCount++;
                if (shortLineCount == pageIdx + 1) {
                    return s;
                }
            }
        }
        return "";
    }

    /**
     * 判断是否为图片型 PDF。
     *
     * <p>判定标准：抽样若干页，如果大多数页都有图片且图片面积占页面 50% 以上，
     * 同时文字层内容极少（每页 <10 字符），则判定为图片型 PDF。
     *
     * <p>典型场景：卦图、漫画、图谱、画册——图片是图形/艺术，不是可 OCR 的文字。
     * 对这种 PDF 做 OCR 只会产生噪声。
     */
    private boolean checkImageBasedPdf(PDDocument pdDoc, int pageCount, int totalCharCount) {
        // 纯图片型 PDF 的判定条件（必须全部满足）：
        // 1. 文字极少（总量 < 500 字符）
        // 2. 图片面积占主导
        // 3. 页数不多（≤100页）——大量页说明是扫描的书/文档，不是画册/卦图
        //    大量页的扫描文档应该走 OCR 获取文字，而不是当图片处理
        if (totalCharCount > 500) {
            log.debug("文字总量 {} > 500，不是图片型 PDF", totalCharCount);
            return false;
        }
        if (pageCount > 100) {
            log.debug("页数 {} > 100，可能是扫描文档而非画册，走 OCR 路径", pageCount);
            return false;
        }
        // 抽样页数（最多抽 5 页，避免大文件耗时）
        int sampleCount = Math.min(5, pageCount);
        int imageHeavyPages = 0;

        for (int i = 0; i < sampleCount; i++) {
            int pageIdx = i * pageCount / sampleCount;  // 均匀抽样
            var page = pdDoc.getPage(pageIdx);
            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            float pageArea = pageWidth * pageHeight;

            // 获取页面中的图片
            var resources = page.getResources();
            if (resources == null) continue;

            int imageCount = 0;
            float imageArea = 0;
            try {
                var xObjectNames = resources.getXObjectNames();
                for (var name : xObjectNames) {
                    var xObject = resources.getXObject(name);
                    if (xObject instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img) {
                        imageCount++;
                        // 估算图片面积（用原始尺寸，不考虑变换矩阵——简化处理）
                        imageArea += img.getWidth() * img.getHeight();
                    }
                }
            } catch (IOException e) {
                // 读取图片信息失败，跳过此页
                continue;
            }

            // 如果页面有图片，且图片面积占页面 30% 以上 → 图片型页面
            if (imageCount > 0 && imageArea > pageArea * 0.3) {
                imageHeavyPages++;
            }
        }

        // 如果抽样的页中，超过一半是图片型 → 判定为图片型 PDF
        boolean isImageBased = sampleCount > 0 && imageHeavyPages > sampleCount / 2;
        if (isImageBased) {
            log.debug("图片型 PDF 判定: {}/{} 抽样页为图片主导", imageHeavyPages, sampleCount);
        }
        return isImageBased;
    }

    /**
     * 把 PDF 每页渲染成图片并 OCR。
     */
    private String ocrPdf(PDDocument pdDoc, int pageCount) throws ParseException {
        StringBuilder allText = new StringBuilder();
        PDFRenderer renderer = new PDFRenderer(pdDoc);

        for (int pageIdx = 0; pageIdx < pageCount; pageIdx++) {
            try {
                BufferedImage image;
                try {
                    image = renderer.renderImageWithDPI(pageIdx, OCR_RENDER_DPI);
                } catch (Exception renderEx) {
                    log.warn("OCR 页面 {} 渲染失败（可能含不支持的 JPEG 编码），跳过: {}", pageIdx + 1, renderEx.getMessage());
                    continue;
                }
                // 渲染成临时图片文件让 OcrEngine 处理
                Path tempImg = java.nio.file.Files.createTempFile("knowly-ocr-page-", ".png");
                ImageIO.write(image, "png", tempImg.toFile());

                try {
                    String pageText = ocrEngine.ocr(tempImg, ocrLanguages);
                    allText.append(pageText);
                    if (!pageText.endsWith("\n")) {
                        allText.append("\n");
                    }
                } finally {
                    java.nio.file.Files.deleteIfExists(tempImg);
                }
            } catch (ParseException e) {
                // 单页 OCR 失败不中断整文档，记录后继续
                log.warn("第 {} 页 OCR 失败: {}", pageIdx + 1, e.getMessage());
                allText.append("[第").append(pageIdx + 1).append("页 OCR 失败]\n");
            } catch (IOException e) {
                log.warn("第 {} 页渲染失败: {}", pageIdx + 1, e.getMessage());
                allText.append("[第").append(pageIdx + 1).append("页渲染失败]\n");
            }
        }
        return allText.toString().strip();
    }

    private HashMap<String, String> buildExtra(PDDocument pdDoc, boolean isScanned) {
        var extra = new HashMap<String, String>();
        extra.put("isScanned", String.valueOf(isScanned));
        if (pdDoc.getDocumentInformation() != null) {
            String title = pdDoc.getDocumentInformation().getTitle();
            if (title != null) extra.put("title", title);
        }
        return extra;
    }
}
