package com.knowly.ingest;

import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.ParseException;
import com.knowly.core.model.RawDocument;
import com.knowly.core.spi.DocumentParser;
import com.knowly.core.spi.OcrEngine;
import com.knowly.ingest.ocr.TesseractOcrEngine;
import com.knowly.ingest.pdf.PdfDocumentParser;
import com.knowly.ingest.tika.TikaDocumentParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 复合文档解析器——按优先级路由到正确的解析器。
 *
 * <p>PDF → {@link PdfDocumentParser}（优先级 10，知了自己控制 OCR）
 * 其他 → {@link TikaDocumentParser}（优先级 0，兜底）
 *
 * <p>关键：TikaDocumentParser 内部禁用了 Tika 的 TesseractOCRParser，
 * 避免 Tika 自作主张对图片做 OCR（产生乱码）。OCR 只由 PdfDocumentParser 控制。
 */
public class CompositeParser implements DocumentParser {

    private final List<DocumentParser> parsers;

    public CompositeParser(OcrEngine ocrEngine, List<String> ocrLanguages) {
        this.parsers = new ArrayList<>();
        // PDF 专用解析器（高优先级）
        this.parsers.add(new PdfDocumentParser(ocrEngine, ocrLanguages));
        // Tika 兜底解析器（低优先级，已禁用内置 OCR）
        this.parsers.add(new TikaDocumentParser());
        // 按优先级降序排列
        this.parsers.sort(Comparator.comparingInt(DocumentParser::priority).reversed());
    }

    /**
     * 简化构造（默认中英文 OCR）。
     */
    public CompositeParser() {
        this(new TesseractOcrEngine(List.of("chi_sim", "eng")), List.of("chi_sim", "eng"));
    }

    @Override
    public boolean supports(String fileName, String mimeType) {
        return parsers.stream().anyMatch(p -> p.supports(fileName, mimeType));
    }

    @Override
    public RawDocument parse(Path filePath) throws ParseException {
        // 找第一个支持的解析器
        String fileName = filePath.getFileName().toString();
        for (DocumentParser parser : parsers) {
            if (parser.supports(fileName, null)) {
                return parser.parse(filePath);
            }
        }
        // 理论上 TikaDocumentParser 兜底（supports 返回 true），不会走到这里
        throw new ParseException(ErrorCode.PARSE_001, "无可用解析器",
                "file=" + filePath);
    }

    @Override
    public int priority() {
        return 100;  // 复合解析器整体优先级最高
    }
}
