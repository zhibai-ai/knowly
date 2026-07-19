package com.knowly.ingest.ocr;

import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.ParseException;
import com.knowly.core.spi.OcrEngine;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tesseract OCR 引擎实现。
 *
 * <p>通过 tess4j（Java 绑定）调用系统 Tesseract。需要系统安装 tesseract-ocr + 中文语言包。
 * 支持 jpg/png/tiff/bmp/gif 等常见图片格式。
 */
public class TesseractOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);

    /** 支持的图片扩展名 */
    private static final List<String> SUPPORTED_FORMATS =
            List.of("jpg", "jpeg", "png", "tif", "tiff", "bmp", "gif");

    /** tess4j 实例（线程安全，可复用） */
    private final ITesseract tesseract;

    /**
     * 创建 OCR 引擎（默认语言中英文）。
     */
    public TesseractOcrEngine() {
        this(List.of("chi_sim", "eng"));
    }

    /**
     * 创建 OCR 引擎（指定语言）。
     *
     * @param languages Tesseract 语言代码列表，如 ["chi_sim", "eng"]
     */
    public TesseractOcrEngine(List<String> languages) {
        this.tesseract = new Tesseract();
        // 设置 tessdata 路径（防止找不到语言包导致崩溃）
        String tessdataPath = findTessdataPath();
        this.tesseract.setDatapath(tessdataPath);
        // 语言用 + 连接，如 "chi_sim+eng"
        this.tesseract.setLanguage(String.join("+", languages));
        // 适度提升精度：开启页面分割模式 3（自动分页，带 OSD）
        this.tesseract.setPageSegMode(3);
        log.debug("TesseractOcrEngine 初始化，语言: {}, tessdata: {}", languages, tessdataPath);
    }

    /** 查找 tessdata 目录 */
    private static String findTessdataPath() {
        // 1. 环境变量
        String env = System.getenv("TESSDATA_PREFIX");
        if (env != null && !env.isEmpty()) return env;
        // 2. 常见路径
        String[] candidates = {
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/4.00/tessdata",
            "/usr/share/tessdata",
            "/usr/local/share/tessdata"
        };
        for (String path : candidates) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(path, "chi_sim.traineddata"))) {
                return path;
            }
        }
        log.warn("未找到 tessdata 目录，OCR 可能无法工作");
        return "/usr/share/tesseract-ocr/5/tessdata";
    }

    @Override
    public boolean supports(String imageFormat) {
        if (imageFormat == null) return false;
        String ext = imageFormat.toLowerCase().replace(".", "");
        return SUPPORTED_FORMATS.contains(ext);
    }

    @Override
    public String ocr(Path imagePath, List<String> languages) throws ParseException {
        log.debug("OCR 识别: file={}, languages={}", imagePath, languages);

        // 如果指定了新语言，临时切换
        if (languages != null && !languages.isEmpty()) {
            tesseract.setLanguage(String.join("+", languages));
        }

        try {
            BufferedImage image = ImageIO.read(imagePath.toFile());
            if (image == null) {
                throw new ParseException(
                        ErrorCode.OCR_001, "图片读取失败",
                        "file=" + imagePath + ", cause=ImageIO 返回 null（格式不支持或文件损坏）");
            }
            String result = tesseract.doOCR(image);
            log.debug("OCR 完成: file={}, 文本长度={}", imagePath, result.length());
            return result.trim();
        } catch (TesseractException e) {
            throw new ParseException(
                    ErrorCode.OCR_001, "OCR 识别失败",
                    "file=" + imagePath + ", cause=" + e.getMessage(), e);
        } catch (java.io.IOException e) {
            throw new ParseException(
                    ErrorCode.OCR_001, "图片文件读取失败",
                    "file=" + imagePath + ", cause=" + e.getMessage(), e);
        }
    }
}
