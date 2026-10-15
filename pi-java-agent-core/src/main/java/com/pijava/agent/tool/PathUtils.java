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

    /**
     * 图片嗅探的字节预算（pi {@code utils/mime.ts:5} {@code IMAGE_TYPE_SNIFF_BYTES}）：
     * read 工具读全文件后取头部判定等价；嗅探只看这个前缀内的字节。
     */
    private static final int IMAGE_TYPE_SNIFF_BYTES = 4100;
    /** Magic bytes for image detection. */
    private static final byte[] PNG_SIG = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_SIG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87_SIG = {0x47, 0x49, 0x46, 0x38, 0x37, 0x61};
    private static final byte[] GIF89_SIG = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61};
    private static final byte[] BMP_SIG = {0x42, 0x4D};
    private static final byte[] RIFF_SIG = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_SIG = {0x57, 0x45, 0x42, 0x50};

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

    /**
     * 嗅探图片 MIME（pi {@code utils/mime.ts:7-27} {@code detectSupportedImageMimeType}，
     * 逐字加严，批 9/B85）。判定只依赖头部 {@link #IMAGE_TYPE_SNIFF_BYTES} 字节；
     * 嗅不出来返回空 ⇒ read 工具按<b>文本</b>处理（与 pi 同）。
     *
     * <ul>
     *   <li>JPEG：{@code FF D8 FF} 且第 4 字节不是 {@code 0xF7}（JPG 保留标记，pi 拒）；</li>
     *   <li>PNG：8 字节全签 ＋ 偏移 8 BE32==13 且偏移 12 为 {@code IHDR}，
     *       且 IDAT 之前没有 {@code acTL}（aPNG 动图拒，只收静态 PNG）；</li>
     *   <li>BMP：{@code BM} 之后还要过 {@link #isBmp} 结构校验。</li>
     * </ul>
     */
    public static Optional<String> detectImageMimeType(byte[] data) {
        if (data == null || data.length < 4) return Optional.empty();
        if (startsWith(data, JPEG_SIG)) {
            // pi mime.ts:11 —— buffer[3] === 0xf7 ? null : image/jpeg
            return data.length > 3 && (data[3] & 0xFF) == 0xF7
                ? Optional.empty() : Optional.of("image/jpeg");
        }
        if (startsWith(data, PNG_SIG)) {
            return isPng(data) && !isAnimatedPng(data)
                ? Optional.of("image/png") : Optional.empty();
        }
        if (startsWith(data, GIF87_SIG) || startsWith(data, GIF89_SIG)) {
            return Optional.of("image/gif");
        }
        if (startsWith(data, RIFF_SIG) && data.length >= 12
            && data[8] == WEBP_SIG[0] && data[9] == WEBP_SIG[1]
            && data[10] == WEBP_SIG[2] && data[11] == WEBP_SIG[3]) {
            return Optional.of("image/webp");
        }
        if (startsWith(data, BMP_SIG) && isBmp(data)) {
            return Optional.of("image/bmp");
        }
        return Optional.empty();
    }

    /** pi {@code mime.ts} {@code isPng}：首块必须是长度 13 的 IHDR。 */
    private static boolean isPng(byte[] buffer) {
        return buffer.length >= 16
            && readUInt32BE(buffer, PNG_SIG.length) == 13
            && asciiAt(buffer, 12, "IHDR");
    }

    /**
     * pi {@code mime.ts} {@code isAnimatedPng}：沿 chunk 走，遇 {@code acTL} 判动图，
     * 遇 {@code IDAT} 即静图。畸形链（前进不了/越界）按非动图处理（与 pi 的 false 同）。
     */
    private static boolean isAnimatedPng(byte[] buffer) {
        int offset = PNG_SIG.length;
        while (offset + 8 <= Math.min(buffer.length, IMAGE_TYPE_SNIFF_BYTES)) {
            long chunkLength = readUInt32BE(buffer, offset);
            int chunkTypeOffset = offset + 4;
            if (asciiAt(buffer, chunkTypeOffset, "acTL")) return true;
            if (asciiAt(buffer, chunkTypeOffset, "IDAT")) return false;
            // length(4) + type(4) + body + crc(4)；防御 0/超长 chunk 死循环。
            long nextOffset = (long) offset + 8 + chunkLength + 4;
            if (nextOffset <= offset || nextOffset > buffer.length) return false;
            offset = (int) nextOffset;
        }
        return false;
    }

    /**
     * pi {@code mime.ts:53-86} {@code isBmp}：校验声明文件大小、像素数据偏移、
     * DIB 头（12 或 40..124）、colorPlanes==1、bitsPerPixel ∈ {1,4,8,16,24,32}。
     */
    private static boolean isBmp(byte[] buffer) {
        if (buffer.length < 26) return false;
        long declaredFileSize = readUInt32LE(buffer, 2);
        long pixelDataOffset = readUInt32LE(buffer, 10);
        long dibHeaderSize = readUInt32LE(buffer, 14);
        if (declaredFileSize != 0 && declaredFileSize < 26) return false;
        if (pixelDataOffset < 14 + dibHeaderSize) return false;
        if (declaredFileSize != 0 && pixelDataOffset >= declaredFileSize) return false;

        int colorPlanes;
        int bitsPerPixel;
        if (dibHeaderSize == 12) {
            colorPlanes = readUInt16LE(buffer, 22);
            bitsPerPixel = readUInt16LE(buffer, 24);
        } else if (dibHeaderSize >= 40 && dibHeaderSize <= 124) {
            if (buffer.length < 30) return false;
            colorPlanes = readUInt16LE(buffer, 26);
            bitsPerPixel = readUInt16LE(buffer, 28);
        } else {
            return false;
        }
        return colorPlanes == 1
            && (bitsPerPixel == 1 || bitsPerPixel == 4 || bitsPerPixel == 8
                || bitsPerPixel == 16 || bitsPerPixel == 24 || bitsPerPixel == 32);
    }

    private static boolean asciiAt(byte[] buffer, int offset, String ascii) {
        if (offset < 0 || offset + ascii.length() > buffer.length) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if (buffer[offset + i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    private static int readUInt16LE(byte[] buffer, int offset) {
        int b0 = offset < buffer.length ? buffer[offset] & 0xFF : 0;
        int b1 = offset + 1 < buffer.length ? buffer[offset + 1] & 0xFF : 0;
        return b0 | (b1 << 8);
    }

    private static long readUInt32LE(byte[] buffer, int offset) {
        long value = 0;
        for (int i = 3; i >= 0; i--) {
            value = (value << 8) | (offset + i < buffer.length ? buffer[offset + i] & 0xFF : 0);
        }
        return value & 0xFFFFFFFFL;
    }

    private static long readUInt32BE(byte[] buffer, int offset) {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (offset + i < buffer.length ? buffer[offset + i] & 0xFF : 0);
        }
        return value & 0xFFFFFFFFL;
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
