package com.knowly.embed.sink.jsonl;

import com.knowly.core.config.SinkConfig;
import com.knowly.core.spi.ChunkSink;
import com.knowly.core.spi.ChunkSinkFactory;
import java.nio.file.Path;

/**
 * {@link JsonlSink} 的工厂。配置项：
 * <ul>
 *   <li>path: JSONL 文件路径（默认 ./data/kb/01-chunks/chunks.jsonl）</li>
 * </ul>
 */
public class JsonlSinkFactory implements ChunkSinkFactory {

    @Override
    public String type() {
        return "jsonl";
    }

    @Override
    public ChunkSink create(SinkConfig config) {
        Path path = Path.of(config.str("path", "./data/kb/01-chunks/chunks.jsonl"));
        return new JsonlSink(path);
    }
}
