package com.pijava.agent.tool.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** 批 9 裁决 D1：JDK 无 WebP 解码器，限内直通靠 RIFF 头解析画布尺寸。 */
class WebpDimensionsTest {

    private static byte[] riff(String fourcc, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        int total = 4 + 8 + payload.length; // WEBP + chunk header + payload
        out.write(total & 0xFF); out.write((total >>> 8) & 0xFF);
        out.write((total >>> 16) & 0xFF); out.write((total >>> 24) & 0xFF);
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(fourcc.getBytes(StandardCharsets.US_ASCII));
        out.write(payload.length & 0xFF); out.write((payload.length >>> 8) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF); out.write((payload.length >>> 24) & 0xFF);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    @Test
    void parsesVp8xCanvasDimensions() {
        // 18 字节 payload：flags4 + reserved3 + 宽-1(3) + 高-1(3) + ...
        byte[] payload = new byte[18];
        int wm1 = 99;   // width 100
        int hm1 = 49;   // height 50
        payload[7] = (byte) wm1; payload[8] = (byte) (wm1 >>> 8); payload[9] = (byte) (wm1 >>> 16);
        payload[10] = (byte) hm1; payload[11] = (byte) (hm1 >>> 8); payload[12] = (byte) (hm1 >>> 16);
        assertThat(WebpDimensions.dimensions(riff("VP8X", payload))).containsExactly(100, 50);
    }

    @Test
    void parsesVp8lPackedDimensions() {
        // signature 0x2F + 14+14 位打包：宽 100(99)、高 50(49)
        byte[] payload = new byte[6];
        payload[0] = 0x2F;
        int bits = 99 | (49 << 14);
        payload[1] = (byte) bits; payload[2] = (byte) (bits >>> 8);
        payload[3] = (byte) (bits >>> 16); payload[4] = (byte) (bits >>> 24);
        assertThat(WebpDimensions.dimensions(riff("VP8L", payload))).containsExactly(100, 50);
    }

    @Test
    void parsesLossyVp8FrameDimensions() {
        byte[] payload = new byte[10];
        payload[0] = (byte) 0x9D; payload[1] = 0x01; payload[2] = 0x2A;
        payload[6] = 100 & 0xFF; payload[7] = 0;
        payload[8] = 50 & 0xFF; payload[9] = 0;
        assertThat(WebpDimensions.dimensions(riff("VP8 ", payload))).containsExactly(100, 50);
    }

    @Test
    void rejectsNonWebpAndBadHeaders() {
        assertThat(WebpDimensions.dimensions("not a webp at all".getBytes(StandardCharsets.US_ASCII)))
            .isNull();
        // VP8L 缺签名
        assertThat(WebpDimensions.dimensions(riff("VP8L", new byte[] {0, 0, 0, 0, 0}))).isNull();
        // VP8 错 start code
        byte[] bad = new byte[10];
        assertThat(WebpDimensions.dimensions(riff("VP8 ", bad))).isNull();
        // 只有容器头没有 bitstream chunk
        assertThat(WebpDimensions.dimensions(
            "RIFF\u0000\u0000\u0000\u0000WEBP".getBytes(StandardCharsets.US_ASCII))).isNull();
    }
}
