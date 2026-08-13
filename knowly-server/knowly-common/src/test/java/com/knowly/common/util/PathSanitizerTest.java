package com.knowly.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PathSanitizer} 单元测试。覆盖穿越防护、白名单边界、正常路径。
 */
class PathSanitizerTest {

    @TempDir
    Path tempDir;

    @Test
    void should_pass_when_path_inside_base_dir() {
        Path base = tempDir;
        Path file = base.resolve("subdir/file.txt");

        Path result = PathSanitizer.sanitize(file, base);

        // 用字符串比较避免 AssertJ 的 toRealPath（要求文件存在）
        assertThat(result.toString()).startsWith(base.toString());
    }

    @Test
    void should_throw_when_path_traversal_attempt() {
        Path base = tempDir.resolve("allowed");
        Path malicious = base.resolve("../../etc/passwd");

        assertThatThrownBy(() -> PathSanitizer.sanitize(malicious, base))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("路径越界");
    }

    @Test
    void should_return_false_when_path_outside_base() {
        Path base = tempDir.resolve("allowed");
        Path outside = tempDir.resolve("other/file.txt");

        boolean safe = PathSanitizer.isSafe(outside, base);

        assertThat(safe).isFalse();
    }

    @Test
    void should_return_true_when_path_inside_base() {
        Path base = tempDir;
        Path inside = base.resolve("nested/deep/file.md");

        boolean safe = PathSanitizer.isSafe(inside, base);

        assertThat(safe).isTrue();
    }

    @Test
    void should_handle_dotdot_in_middle_correctly() {
        Path base = tempDir;
        // base/sub/../sub/file → normalize 后仍在 base 内
        Path path = base.resolve("sub/../sub/file.txt");

        Path result = PathSanitizer.sanitize(path, base);

        assertThat(PathSanitizer.isSafe(result, base)).isTrue();
    }
}
