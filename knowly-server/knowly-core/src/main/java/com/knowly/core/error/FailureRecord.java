package com.knowly.core.error;

/**
 * 单个文件的错误记录。
 *
 * @param filePath    文件路径
 * @param documentId  文档 ID
 * @param stage       失败阶段
 * @param errorCode   错误码
 * @param message     错误信息
 */
public record FailureRecord(
        String filePath,
        String documentId,
        String stage,
        String errorCode,
        String message
) {}
