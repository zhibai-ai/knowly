package com.knowly.common.exception;

/** 产出层异常：向量库/文件写入失败 */
public class SinkException extends KnowlyException {
    public SinkException(String errorCode, String message) { super(errorCode, message); }
    public SinkException(String errorCode, String message, String detail) { super(errorCode, message, detail); }
    public SinkException(String errorCode, String message, String detail, Throwable cause) { super(errorCode, message, detail, cause); }
}
