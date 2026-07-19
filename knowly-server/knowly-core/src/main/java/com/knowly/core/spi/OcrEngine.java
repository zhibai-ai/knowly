package com.knowly.core.spi;

import com.knowly.common.exception.ParseException;
import java.nio.file.Path;
import java.util.List;

/**
 * OCR 引擎 SPI。默认实现 TesseractOcrEngine。
 */
public interface OcrEngine {
    /** 该引擎是否支持此图片格式 */
    boolean supports(String imageFormat);
    /** 对图片做 OCR 识别 */
    String ocr(Path imagePath, List<String> languages) throws ParseException;
}
