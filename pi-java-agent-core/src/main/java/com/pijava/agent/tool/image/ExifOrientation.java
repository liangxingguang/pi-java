package com.pijava.agent.tool.image;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * EXIF 朝向解析与应用（移植 pi {@code utils/exif-orientation.ts}，批 9/B85）。
 *
 * <p>pi 只在 JPEG（{@code FF D8}）与 WebP（{@code RIFF....WEBP}）字节里找 EXIF；
 * TIFF 头按 {@code II}/{@code MM} 两种字节序解析 IFD0 的 {@code 0x0112} 标签。
 * 朝向 1（或缺席）直返原图；2-8 用与 pi 相同的 fliph/flipv/90° 旋转序列作用在
 * 已解码图像上（仅重编码路径调用——限内直通时 pi 同样丢弃 EXIF 变换）。</p>
 */
final class ExifOrientation {

    private ExifOrientation() {}

    /** 按字节中携带的 EXIF 朝向返回变换后的图像；朝向 1/不可解析时原样返回。 */
    static BufferedImage apply(BufferedImage image, byte[] bytes) {
        return switch (orientation(bytes)) {
            case 2 -> flipH(image);
            case 3 -> flipV(flipH(image));
            case 4 -> flipV(image);
            case 5 -> flipH(rotateCw(image));
            case 6 -> rotateCw(image);
            case 7 -> flipH(rotateCcw(image));
            case 8 -> rotateCcw(image);
            default -> image;
        };
    }

    // ── 解析（exif-orientation.ts getExifOrientation） ───────────────────

    static int orientation(byte[] bytes) {
        int tiffOffset = -1;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            tiffOffset = findJpegTiffOffset(bytes);
        } else if (bytes.length >= 12
            && (bytes[0] & 0xFF) == 0x52 && (bytes[1] & 0xFF) == 0x49
            && (bytes[2] & 0xFF) == 0x46 && (bytes[3] & 0xFF) == 0x46
            && (bytes[8] & 0xFF) == 0x57 && (bytes[9] & 0xFF) == 0x45
            && (bytes[10] & 0xFF) == 0x42 && (bytes[11] & 0xFF) == 0x50) {
            tiffOffset = findWebpTiffOffset(bytes);
        }
        if (tiffOffset == -1) return 1;
        return readOrientationFromTiff(bytes, tiffOffset);
    }

    private static int findJpegTiffOffset(byte[] bytes) {
        int offset = 2;
        while (offset < bytes.length - 1) {
            if ((bytes[offset] & 0xFF) != 0xFF) return -1;
            int marker = bytes[offset + 1] & 0xFF;
            if (marker == 0xFF) {
                offset++;
                continue;
            }
            if (marker == 0xE1) {
                int segmentStart = offset + 4;
                if (segmentStart + 6 > bytes.length) return -1;
                if (hasExifHeader(bytes, segmentStart)) return segmentStart + 6;
            }
            if (offset + 4 > bytes.length) return -1;
            int length = ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
            offset += 2 + length;
        }
        return -1;
    }

    private static int findWebpTiffOffset(byte[] bytes) {
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String chunkId = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = (bytes[offset + 4] & 0xFF)
                | ((bytes[offset + 5] & 0xFF) << 8)
                | ((bytes[offset + 6] & 0xFF) << 16)
                | ((bytes[offset + 7] & 0xFF) << 24);
            int dataStart = offset + 8;
            if ("EXIF".equals(chunkId)) {
                if (dataStart + chunkSize > bytes.length) return -1;
                // 部分 WebP 在 TIFF 头前多一个 "Exif\0\0" 前缀。
                return chunkSize >= 6 && hasExifHeader(bytes, dataStart) ? dataStart + 6 : dataStart;
            }
            // RIFF chunk 偶对齐填充。
            offset = dataStart + chunkSize + (chunkSize % 2);
        }
        return -1;
    }

    private static boolean hasExifHeader(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) == 0x45 && (bytes[offset + 1] & 0xFF) == 0x78
            && (bytes[offset + 2] & 0xFF) == 0x69 && (bytes[offset + 3] & 0xFF) == 0x66
            && (bytes[offset + 4] & 0xFF) == 0x00 && (bytes[offset + 5] & 0xFF) == 0x00;
    }

    private static int readOrientationFromTiff(byte[] bytes, int tiffStart) {
        if (tiffStart + 8 > bytes.length) return 1;
        int byteOrder = ((bytes[tiffStart] & 0xFF) << 8) | (bytes[tiffStart + 1] & 0xFF);
        boolean le = byteOrder == 0x4949;

        int ifdOffset = read32(bytes, tiffStart + 4, le);
        int ifdStart = tiffStart + ifdOffset;
        if (ifdStart + 2 > bytes.length) return 1;

        int entryCount = read16(bytes, ifdStart, le);
        for (int i = 0; i < entryCount; i++) {
            int entryPos = ifdStart + 2 + i * 12;
            if (entryPos + 12 > bytes.length) return 1;
            if (read16(bytes, entryPos, le) == 0x0112) {
                int value = read16(bytes, entryPos + 8, le);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int read16(byte[] b, int pos, boolean le) {
        if (pos + 1 >= b.length) return 0;
        return le ? (b[pos] & 0xFF) | ((b[pos + 1] & 0xFF) << 8)
                  : ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
    }

    private static int read32(byte[] b, int pos, boolean le) {
        if (pos + 3 >= b.length) return 0;
        return le ? (b[pos] & 0xFF) | ((b[pos + 1] & 0xFF) << 8)
                    | ((b[pos + 2] & 0xFF) << 16) | ((b[pos + 3] & 0xFF) << 24)
                  : ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                    | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
    }

    // ── 几何变换（对应 pi 的 fliph/flipv/rotate90） ───────────────────────

    /** TYPE_CUSTOM（0）不能直接进 BufferedImage 构造，退化到 ARGB。 */
    private static BufferedImage sameShape(BufferedImage src, int width, int height) {
        int type = src.getType();
        return new BufferedImage(width, height,
            type == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : type);
    }

    private static BufferedImage flipH(BufferedImage src) {
        var dst = sameShape(src, src.getWidth(), src.getHeight());
        Graphics2D g = dst.createGraphics();
        g.translate(src.getWidth(), 0);
        g.scale(-1, 1);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    private static BufferedImage flipV(BufferedImage src) {
        var dst = sameShape(src, src.getWidth(), src.getHeight());
        Graphics2D g = dst.createGraphics();
        g.translate(0, src.getHeight());
        g.scale(1, -1);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    /** pi rotate90 索引 A（case 5/6）：src(x,y)→dst(h-1-y,x)，即顺时针 90°。 */
    private static BufferedImage rotateCw(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        var dst = sameShape(src, h, w);
        Graphics2D g = dst.createGraphics();
        g.translate(h, 0);
        g.rotate(Math.PI / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    /** pi rotate90 索引 B（case 7/8）：src(x,y)→dst(y,w-1-x)，即逆时针 90°。 */
    private static BufferedImage rotateCcw(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        var dst = sameShape(src, h, w);
        Graphics2D g = dst.createGraphics();
        g.translate(0, w);
        g.rotate(-Math.PI / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }
}
