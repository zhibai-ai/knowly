package com.knowly.embed.sink.markdown;

import com.knowly.core.config.SinkConfig;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.ChunkSinkFactory;
import java.nio.file.Path;

/**
 * {@link MarkdownSink} 的工厂。配置项：
 * <ul>
 *   <li>dir: 输出目录（默认 ./data/kb/00-clean）</li>
 *   <li>include_chunk_markers: 是否嵌入 chunk 边界 HTML 注释（默认 true）</li>
 *   <li>keep_source_tree: 是否按原文档目录结构组织（默认 false）</li>
 * </ul>
 */
public class MarkdownSinkFactory implements ChunkSinkFactory {

    @Override
    public String type() {
        return "markdown";
    }

    @Override
    public ChunkSink create(SinkConfig config) {
        Path dir = Path.of(config.str("dir", "./data/kb/00-clean"));
        boolean includeMarkers = config.bool("include_chunk_markers", true);
        boolean keepTree = config.bool("keep_source_tree", false);
        return new MarkdownSink(dir, includeMarkers, keepTree);
    }
}
