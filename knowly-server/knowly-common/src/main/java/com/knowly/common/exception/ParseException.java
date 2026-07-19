package com.knowly.common.exception;

/** 摄取层异常：文档解析/OCR/版面分析失败 */
public class ParseException extends KnowlyException {
    public ParseException(String errorCode, String message) { super(errorCode, message); }
    public ParseException(String errorCode, String message, String detail) { super(errorCode, message, detail); }
    public ParseException(String errorCode, String message, String detail, Throwable cause) { super(errorCode, message, detail, cause); }
}
