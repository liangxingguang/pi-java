package com.pijava.agent.tool.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * 批 9/B85：EXIF 朝向解析（移植 pi {@code utils/exif-orientation.ts}）。
 * 夹具＝ImageIO 写 JPEG 后在 SOI 后手工插入 APP1/EXIF 段（TIFF IFD0 0x0112）。
 */
class ExifOrientationTest {

    private static byte[] jpegWithExifOrientation(int orientation, int width, int height)
            throws Exception {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2 + 1, height);
        g.dispose();
        var raw = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", raw);
        byte[] jpeg = raw.toByteArray();

        // TIFF（little endian）：II 2A00 + IFD offset 8；IFD：1 entry（Orientation SHORT）。
        var tiff = new ByteArrayOutputStream();
        tiff.write('I'); tiff.write('I');
        tiff.write(0x2A); tiff.write(0x00);
        writeLe32(tiff, 8);
        writeLe16(tiff, 1);
        writeLe16(tiff, 0x0112); // tag
        writeLe16(tiff, 3);      // type SHORT
        writeLe32(tiff, 1);      // count
        writeLe16(tiff, orientation);
        writeLe16(tiff, 0);      // pad
        writeLe32(tiff, 0);      // next IFD offset
        byte[] tiffBytes = tiff.toByteArray();

        byte[] app1 = exifApp1(tiffBytes);

        // EXIF-first（相机 JPEG 标准形状：APP1 紧跟 SOI，可与 JFIF APP0 共存）。
        var out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);
        out.write(app1);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] exifApp1(byte[] tiffBytes) {
        var app1 = new ByteArrayOutputStream();
        app1.write(0xFF); app1.write(0xE1);
        int segmentLength = tiffBytes.length + 6 + 2; // Exif\0\0 + length field
        // JPEG marker segment length is BIG-endian（TIFF 内部才是 LE）。
        app1.write((segmentLength >>> 8) & 0xFF);
        app1.write(segmentLength & 0xFF);
        app1.writeBytes("Exif\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        app1.writeBytes(tiffBytes);
        return app1.toByteArray();
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    @Test
    void parsesOrientationFromJpegApp1() throws Exception {
        assertThat(ExifOrientation.orientation(jpegWithExifOrientation(6, 3, 2))).isEqualTo(6);
        assertThat(ExifOrientation.orientation(jpegWithExifOrientation(3, 3, 2))).isEqualTo(3);
        assertThat(ExifOrientation.orientation(jpegWithExifOrientation(1, 3, 2))).isEqualTo(1);
    }

    @Test
    void jpegWithoutExifDefaultsToOne() throws Exception {
        var img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var raw = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", raw);
        assertThat(ExifOrientation.orientation(raw.toByteArray())).isEqualTo(1);
    }

    @Test
    void orientation6RotatesPortraitBytesToLandscapePixels() throws Exception {
        byte[] bytes = jpegWithExifOrientation(6, 4, 3);
        var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        var oriented = ExifOrientation.apply(decoded, bytes);
        // 编码字节是 4x3，朝向 6（顺时针 90°）后显示为 3x4。
        assertThat(oriented.getWidth()).isEqualTo(3);
        assertThat(oriented.getHeight()).isEqualTo(4);
    }

    @Test
    void orientation3RotatesWithoutSwappingDimensions() throws Exception {
        byte[] bytes = jpegWithExifOrientation(3, 4, 3);
        var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        var oriented = ExifOrientation.apply(decoded, bytes);
        assertThat(oriented.getWidth()).isEqualTo(4);
        assertThat(oriented.getHeight()).isEqualTo(3);
    }
}
