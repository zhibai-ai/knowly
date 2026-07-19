package com.knowly.common.util;

import java.nio.file.Path;

/**
 * 路径安全校验工具（防穿越）。
 *
 * <p>本机场景下用户输入文件路径，仍需防止恶意路径输入（如 {@code ../../etc/passwd}）。
 * 校验逻辑：{@code Path.normalize()} 后，路径必须在白名单基目录内。
 */
public final class PathSanitizer {

    private PathSanitizer() {}

    /**
     * 校验路径在白名单基目录内。不合法抛 SecurityException。
     *
     * @param path    待校验的路径（用户输入）
     * @param baseDir 白名单基目录（允许访问的根）
     * @return 规范化后的安全路径
     * @throws SecurityException 如果路径在基目录外或含穿越
     */
    public static Path sanitize(Path path, Path baseDir) {
        Path normalized = path.toAbsolutePath().normalize();
        Path normalizedBase = baseDir.toAbsolutePath().normalize();

        if (!normalized.startsWith(normalizedBase)) {
            throw new SecurityException(
                    "路径越界: " + path + " 不在允许目录 " + baseDir + " 内");
        }
        return normalized;
    }

    /**
     * 判断路径是否在白名单基目录内（不抛异常，返回布尔）。
     *
     * @param path    待校验的路径
     * @param baseDir 白名单基目录
     * @return true 如果安全
     */
    public static boolean isSafe(Path path, Path baseDir) {
        Path normalized = path.toAbsolutePath().normalize();
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        return normalized.startsWith(normalizedBase);
    }
}
