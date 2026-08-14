package com.pijava.agent.tool;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * Path resolution utilities for tools.
 * Aligned with pi's {@code path-utils.ts}.
 */
public final class PathUtils {
    private PathUtils() {}

    /** Magic bytes for image detection. */
    private static final byte[] PNG_SIG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG_SIG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87_SIG = {0x47, 0x49, 0x46, 0x38, 0x37, 0x61};
    private static final byte[] GIF89_SIG = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
    private static final byte[] BMP_SIG = {0x42, 0x4D};
    private static final byte[] RIFF_SIG = {0x52, 0x49, 0x46, 0x46};

    /**
     * Resolve a tool path: relative → absolute, normalize, reject traversal.
     *
     * @throws SecurityException if the resolved path escapes the working directory
     *         (e.g. {@code ../} attempts to access parent directories)
     */
    public static String resolveToolPath(ToolContext ctx, String path) {
        // Relative paths resolve against the ToolContext working directory,
        // not the JVM process directory.
        Path requested = Path.of(path);
        String resolved = requested.isAbsolute()
            ? requested.normalize().toString()
            : Path.of(ctx.cwd()).resolve(requested).normalize().toString();
        String cwd = Path.of(ctx.cwd()).toAbsolutePath().normalize().toString();
        // Normalize both paths to handle trailing separators and case differences
        Path resolvedPath = Path.of(resolved).toAbsolutePath().normalize();
        Path cwdPath = Path.of(cwd).toAbsolutePath().normalize();
        if (!resolvedPath.startsWith(cwdPath)) {
            throw new SecurityException(
                "Path traversal detected: '" + path + "' resolves to '" + resolved
                + "' which is outside the working directory '" + cwd + "'");
        }
        return resolved;
    }

    /** Detect image MIME type from file bytes. */
    public static Optional<String> detectImageMimeType(byte[] data) {
        if (data == null || data.length < 4) return Optional.empty();
        if (startsWith(data, PNG_SIG)) return Optional.of("image/png");
        if (startsWith(data, JPEG_SIG)) return Optional.of("image/jpeg");
        if (startsWith(data, GIF87_SIG) || startsWith(data, GIF89_SIG)) return Optional.of("image/gif");
        if (startsWith(data, BMP_SIG)) return Optional.of("image/bmp");
        if (startsWith(data, RIFF_SIG) && data.length >= 12
            && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    /** Encode bytes as base64 (for image content). */
    public static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
