package com.knowly.common.exception;

/**
 * 错误码常量。格式：{阶段}_{序号}。
 * 新增错误码必须在此登记。错误码是公开契约的一部分，已发布的不可变更。
 */
public final class ErrorCode {
    private ErrorCode() {}

    // ── 通用 ──
    public static final String CONFIG_001 = "配置文件格式错误";
    public static final String CONFIG_002 = "必填配置项缺失";
    public static final String CONFIG_003 = "配置依赖关系不满足";
    public static final String CONFIG_004 = "配置值非法";
    public static final String CONFIG_005 = "未注册的组件类型";
    public static final String CONFIG_006 = "环境变量解析失败";

    // ── 摄取层（INGEST）──
    public static final String PARSE_001 = "文档解析失败";
    public static final String PARSE_002 = "PDF 文本提取失败";
    public static final String PARSE_003 = "PDF 损坏，xref 表异常";
    public static final String PARSE_004 = "扫描版 PDF 检测失败";
    public static final String OCR_001 = "OCR 识别失败";
    public static final String LAYOUT_001 = "版面分析失败";

    // ── 清洗层（CLEAN）──
    public static final String CLEAN_001 = "编码检测/转换失败";
    public static final String CLEAN_002 = "文本清洗失败";
    public static final String CHUNK_001 = "分段失败";
    public static final String DEDUP_001 = "去重失败";

    // ── 向量化层（EMBED）──
    public static final String EMBED_001 = "Embedding API 调用失败";
    public static final String EMBED_002 = "Embedding API 限流";
    public static final String EMBED_003 = "Embedding 维度不匹配";

    // ── 产出层（SINK）──
    public static final String SINK_001 = "向量库写入失败";
    public static final String SINK_002 = "向量库连接失败";
    public static final String SINK_003 = "文件写入失败";

    // ── 安全 ──
    public static final String SEC_001 = "文件类型不在白名单";
    public static final String SEC_002 = "文件大小超限";
    public static final String SEC_003 = "路径穿越被拦截";
    public static final String SEC_004 = "认证失败";

    // ── 任务 ──
    public static final String JOB_001 = "已有清洗任务在运行中";
    public static final String JOB_002 = "任务不存在";
    public static final String JOB_003 = "任务状态不允许此操作";
}
