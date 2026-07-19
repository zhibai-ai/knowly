package com.knowly.core.spi;

import com.knowly.common.exception.ParseException;
import com.knowly.core.model.RawDocument;
import java.nio.file.Path;

/**
 * 文档解析器 SPI。把文件解析成 RawDocument。
 * 实现类通过注册表（ServiceLoader 或 Spring @Component）注册。
 */
public interface DocumentParser {
    /** 该解析器是否支持此文件 */
    boolean supports(String fileName, String mimeType);
    /** 解析文件为 RawDocument */
    RawDocument parse(Path filePath) throws ParseException;
    /** 解析器优先级（多解析器都能处理时，取优先级高的） */
    default int priority() { return 0; }
}
