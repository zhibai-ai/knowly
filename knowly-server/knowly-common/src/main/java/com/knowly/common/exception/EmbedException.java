package com.knowly.common.exception;

/** 向量化层异常：embedding API 调用失败 */
public class EmbedException extends KnowlyException {
    public EmbedException(String errorCode, String message) { super(errorCode, message); }
    public EmbedException(String errorCode, String message, String detail) { super(errorCode, message, detail); }
    public EmbedException(String errorCode, String message, String detail, Throwable cause) { super(errorCode, message, detail, cause); }
}
