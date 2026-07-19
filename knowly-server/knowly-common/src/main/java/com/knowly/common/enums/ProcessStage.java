package com.knowly.common.enums;

/**
 * 流水线处理阶段。每个文件按此顺序依次经过各阶段。
 *
 * <ul>
 *   <li>{@link #INGEST} - 摄取：文件入库、元信息登记</li>
 *   <li>{@link #CLEAN} - 清洗：解析、切块、规整化</li>
 *   <li>{@link #EMBED} - 向量化：调用 embedding 模型生成向量</li>
 *   <li>{@link #SINK} - 落库：写入向量库 / 关系库</li>
 * </ul>
 */
public enum ProcessStage {
    INGEST("摄取"),
    CLEAN("清洗"),
    EMBED("向量化"),
    SINK("落库");

    private final String description;

    ProcessStage(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
