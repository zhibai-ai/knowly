package com.knowly.ingest.tika;

import com.knowly.common.enums.ParseStatus;
import com.knowly.common.exception.ErrorCode;
import com.knowly.common.exception.ParseException;
import com.knowly.common.util.HashUtils;
import com.knowly.common.util.TextNormalizer;
import com.knowly.core.model.DocumentMetadata;
import com.knowly.core.model.RawDocument;
import com.knowly.core.spi.DocumentParser;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用文档解析器——基于 Apache Tika 自动检测格式并提取文本。
 *
 * <p>兜底处理 Word(.doc/.docx)/PPT(.ppt/.pptx)/Excel(.xls/.xlsx)/HTML/TXT/Markdown/RTF 等。
 * PDF 由 {@link com.knowly.ingest.pdf.PdfDocumentParser} 专门处理（优先级更高）。
 */
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

    /** Tika 最大提取字符数（-1 表示无限制） */
    private static final int MAX_CHARS = -1;

    private final AutoDetectParser parser;

    public TikaDocumentParser() {
        // 加载自定义 TikaConfig（禁用内置 OCR），禁用失败则用默认配置
        AutoDetectParser p;
        try (InputStream configStream = TikaDocumentParser.class.getResourceAsStream("/tika-config.xml")) {
            if (configStream != null) {
                TikaConfig tikaConfig = new TikaConfig(configStream);
                p = new AutoDetectParser(tikaConfig);
            } else {
                p = new AutoDetectParser();
            }
        } catch (Exception e) {
            log.warn("加载 tika-config.xml 失败，使用默认配置: {}", e.getMessage());
            p = new AutoDetectParser();
        }
        this.parser = p;
    }

    @Override
    public boolean supports(String fileName, String mimeType) {
        // Tika 兜底支持所有格式；PDF 交给 PdfDocumentParser（优先级更高）
        // 这里返回 true 让 Tika 作为最后的兜底
        return true;
    }

    @Override
    public int priority() {
        // 最低优先级（兜底）。PdfDocumentParser 优先级更高，会先匹配 PDF。
        return 0;
    }

    @Override
    public RawDocument parse(Path filePath) throws ParseException {
        log.debug("Tika 解析: {}", filePath);

        try {
            BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
            Metadata tikaMetadata = new Metadata();
            ParseContext context = new ParseContext();

            try (InputStream stream = java.nio.file.Files.newInputStream(filePath)) {
                tikaMetadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filePath.getFileName().toString());
                parser.parse(stream, handler, tikaMetadata, context);
            }

            String content = handler.toString();
            String fileName = filePath.getFileName().toString();
            String format = detectFormat(fileName, tikaMetadata);

            // 内容哈希（normalize 后）
            String normalized = TextNormalizer.normalize(content);
            String contentHash = HashUtils.contentHash(normalized);
            String documentId = HashUtils.documentId(contentHash);

            DocumentMetadata metadata = buildMetadata(filePath, tikaMetadata, format);
            ParseStatus status = content.isBlank() ? ParseStatus.PARTIAL : ParseStatus.SUCCESS;

            log.debug("Tika 解析完成: {}, format={}, 文本长度={}", filePath, format, content.length());

            return new RawDocument(
                    documentId,
                    filePath.toString(),
                    fileName,
                    format,
                    content,
                    metadata,
                    contentHash,
                    status
            );
        } catch (ParseException e) {
            throw e;
        } catch (Exception e) {
            throw new ParseException(
                    ErrorCode.PARSE_001, "文档解析失败",
                    "file=" + filePath + ", cause=" + e.getMessage(), e);
        }
    }

    /** 从文件名和 Tika metadata 推断格式标识 */
    private String detectFormat(String fileName, Metadata tikaMetadata) {
        String mimeType = tikaMetadata.get("Content-Type");
        if (mimeType != null) {
            // 如 "application/vnd.openxmlformats-officedocument.wordprocessingml.document" → "docx"
            String[] parts = mimeType.split("[/\\.]");
            if (parts.length > 0) {
                String last = parts[parts.length - 1];
                if (!last.isBlank()) return last;
            }
        }
        // 退而求其次从扩展名
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "unknown";
    }

    /** 构建 DocumentMetadata */
    private DocumentMetadata buildMetadata(Path filePath, Metadata tikaMetadata, String format) {
        long fileSize;
        try {
            fileSize = java.nio.file.Files.size(filePath);
        } catch (Exception e) {
            fileSize = -1;
        }

        String author = tikaMetadata.get(TikaCoreProperties.CREATOR);
        String created = tikaMetadata.get(TikaCoreProperties.CREATED);

        LocalDateTime createdTime = null;
        if (created != null) {
            try {
                createdTime = LocalDateTime.parse(created.substring(0, Math.min(created.length(), 19))
                        .replace("T", "T"));
            } catch (Exception ignored) {
                // 时间解析失败不影响解析
            }
        }

        // extra 存 Tika 的额外 metadata（供下游参考）
        var extra = new HashMap<String, String>();
        String title = tikaMetadata.get(TikaCoreProperties.TITLE);
        if (title != null) extra.put("title", title);
        String contentType = tikaMetadata.get("Content-Type");
        if (contentType != null) extra.put("contentType", contentType);

        return new DocumentMetadata(
                fileSize,
                0,  // Tika 兜底解析不精确识别页数，PDF 由 PdfDocumentParser 填
                author,
                createdTime,
                "TikaDocumentParser",
                extra
        );
    }
}
