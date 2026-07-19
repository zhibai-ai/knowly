package com.knowly.common.exception;

/** 配置异常：YAML 格式错误/必填缺失/依赖不满足 */
public class ConfigException extends KnowlyException {
    public ConfigException(String errorCode, String message) { super(errorCode, message); }
    public ConfigException(String errorCode, String message, String detail) { super(errorCode, message, detail); }
    public ConfigException(String errorCode, String message, String detail, Throwable cause) { super(errorCode, message, detail, cause); }
}
