package com.knowly.common.enums;

/**
 * TextChunk 的块类型。
 *
 * <p>借鉴 Unstructured 的 Element 抽象，用 {@code blockType} 字段区分不同块类型，
 * 而非为每种类型建立并列的子类体系。
 *
 * <ul>
 *   <li>{@link #HEADING} - 标题</li>
 *   <li>{@link #PARAGRAPH} - 段落</li>
 *   <li>{@link #LIST} - 列表</li>
 *   <li>{@link #TABLE} - 表格</li>
 *   <li>{@link #QUOTE} - 引用</li>
 *   <li>{@link #CODE} - 代码</li>
 * </ul>
 */
public enum BlockType {
    HEADING("标题"),
    PARAGRAPH("段落"),
    LIST("列表"),
    TABLE("表格"),
    QUOTE("引用"),
    CODE("代码");

    private final String description;

    BlockType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
