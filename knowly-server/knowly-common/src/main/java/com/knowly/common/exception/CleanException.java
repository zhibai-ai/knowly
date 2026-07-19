package com.knowly.common.exception;

/** 清洗层异常：编码/去噪/去重/分段失败 */
public class CleanException extends KnowlyException {
    public CleanException(String errorCode, String message) { super(errorCode, message); }
    public CleanException(String errorCode, String message, String detail) { super(errorCode, message, detail); }
    public CleanException(String errorCode, String message, String detail, Throwable cause) { super(errorCode, message, detail, cause); }
}
