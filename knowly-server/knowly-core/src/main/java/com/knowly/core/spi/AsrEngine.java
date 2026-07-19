package com.knowly.core.spi;

import com.knowly.common.exception.ParseException;
import java.nio.file.Path;

/**
 * ASR 引擎 SPI（v0.2）。v0.1 仅定义接口，不实现。
 */
public interface AsrEngine {
    /** 对音频/视频做语音转写 */
    String transcribe(Path mediaPath) throws ParseException;
}
