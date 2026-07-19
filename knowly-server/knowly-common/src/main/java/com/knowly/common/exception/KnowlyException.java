package com.knowly.common.exception;

/**
 * 知了异常基类。一律非受检（继承 RuntimeException），避免 throws 在 SPI 接口间蔓延。
 * 每个异常携带：错误码（稳定契约）+ 用户友好消息 + 上下文详情（文件/阶段/原因）。
 */
public class KnowlyException extends RuntimeException {
    private final String errorCode;

    public KnowlyException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public KnowlyException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public KnowlyException(String errorCode, String message, String detail) {
        super(message + " | detail: " + detail);
        this.errorCode = errorCode;
    }

    public KnowlyException(String errorCode, String message, String detail, Throwable cause) {
        super(message + " | detail: " + detail, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
