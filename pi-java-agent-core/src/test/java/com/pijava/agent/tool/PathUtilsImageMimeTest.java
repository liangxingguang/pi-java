package com.pijava.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * 批 9/B85：图片 MIME 嗅探逐字对齐 pi {@code utils/mime.ts}
 * {@code detectSupportedImageMimeType}——JPEG 0xF7 拒、PNG 查 IHDR/拒 aPNG、
 * BMP 结构校验。
 */
class PathUtilsImageMimeTest {

    private static byte[] png(int width, int height, boolean animated) {
        // 自构最小 PNG 块：签名 + IHDR(length 13) + 可选 acTL + IDAT + IEND。
        var out = new java.io.ByteArrayOutputStream();
        try {
            out.write(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            if (animated) {
                byte[] actl = new byte[] {0, 0, 0, 8, 'a', 'c', 'T', 'L',
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                out.write(actl);
            }
            byte[] ihdr = new byte[25];
            writeBe(ihdr, 0, 13);
            ascii(ihdr, 4, "IHDR");
            writeBe(ihdr, 8, width);
            writeBe(ihdr, 12, height);
            ihdr[16] = 8; // bit depth
            // 17..24 depth/color/compression/etc+crc 留 0，校验只看类型/长度
            out.write(ihdr);
            byte[] idat = {0, 0, 0, 1, 'I', 'D', 'A', 'T', 0, 0, 0, 0, 0};
            out.write(idat);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        return out.toByteArray();
    }

    /** pi image-process.test.ts createTinyBmp1x1Red24bpp 的 Java 同构。 */
    private static byte[] tinyBmp(int planes, int bpp) {
        byte[] b = new byte[58];
        ascii(b, 0, "BM");
        writeLe(b, 2, b.length);
        writeLe(b, 10, 54);
        writeLe(b, 14, 40);
        writeLe(b, 18, 1);
        writeLe(b, 22, 1);
        writeLe16(b, 26, planes);
        writeLe16(b, 28, bpp);
        writeLe(b, 34, 4);
        b[56] = (byte) 0xFF;
        return b;
    }

    @Test
    void jpegSofMarkerF7IsRejected() {
        // FF D8 FF F7：pi mime.ts:11 明确判 null。
        assertThat(PathUtils.detectImageMimeType(new byte[] {(byte) 0xFF, (byte) 0xD8,
            (byte) 0xFF, (byte) 0xF7, 0, 0})).isEmpty();
    }

    @Test
    void jpegNormalSofMarkerPasses() {
        assertThat(PathUtils.detectImageMimeType(new byte[] {(byte) 0xFF, (byte) 0xD8,
            (byte) 0xFF, (byte) 0xE0})).contains("image/jpeg");
    }

    @Test
    void staticPngPassesAndAnimatedPngIsRejected() {
        assertThat(PathUtils.detectImageMimeType(png(1, 1, false))).contains("image/png");
        assertThat(PathUtils.detectImageMimeType(png(1, 1, true))).isEmpty();
    }

    @Test
    void pngStubWithoutIhdrIsRejected() {
        // 旧 ReadToolTest 的 20 字节全零桩：pi 也会按文本读。
        byte[] stub = new byte[20];
        stub[0] = (byte) 0x89; stub[1] = 0x50; stub[2] = 0x4E; stub[3] = 0x47;
        stub[4] = 0x0D; stub[5] = 0x0A; stub[6] = 0x1A; stub[7] = 0x0A;
        assertThat(PathUtils.detectImageMimeType(stub)).isEmpty();
    }

    @Test
    void gif87AndGif89BothDetect() {
        assertThat(PathUtils.detectImageMimeType("GIF87a".getBytes(StandardCharsets.US_ASCII)))
            .contains("image/gif");
        assertThat(PathUtils.detectImageMimeType("GIF89a".getBytes(StandardCharsets.US_ASCII)))
            .contains("image/gif");
    }

    @Test
    void webpRequiresRiffAndWebpSignatures() {
        byte[] riff = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0,
            'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        assertThat(PathUtils.detectImageMimeType(riff)).contains("image/webp");
        assertThat(PathUtils.detectImageMimeType("RIFFxxxxWAVX".getBytes())).isEmpty();
    }

    @Test
    void bmpRequiresHeaderValidation() {
        assertThat(PathUtils.detectImageMimeType(tinyBmp(1, 24))).contains("image/bmp");
        // planes!=1 拒
        assertThat(PathUtils.detectImageMimeType(tinyBmp(2, 24))).isEmpty();
        // bpp 不在白名单拒
        assertThat(PathUtils.detectImageMimeType(tinyBmp(1, 15))).isEmpty();
        // 裸 "BM" 短缓冲拒（旧实现会误判）
        assertThat(PathUtils.detectImageMimeType("BM".getBytes(StandardCharsets.US_ASCII))).isEmpty();
    }

    @Test
    void textFileSniffsEmpty() {
        assertThat(PathUtils.detectImageMimeType("hello text".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(Optional.empty());
    }

    private static void ascii(byte[] b, int offset, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[offset + i] = (byte) s.charAt(i);
        }
    }

    private static void writeBe(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >>> 24);
        b[offset + 1] = (byte) (value >>> 16);
        b[offset + 2] = (byte) (value >>> 8);
        b[offset + 3] = (byte) value;
    }

    private static void writeLe(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >>> 8);
        b[offset + 2] = (byte) (value >>> 16);
        b[offset + 3] = (byte) (value >>> 24);
    }

    private static void writeLe16(byte[] b, int offset, int value) {
        b[offset] = (byte) value;
        b[offset + 1] = (byte) (value >>> 8);
    }
}
