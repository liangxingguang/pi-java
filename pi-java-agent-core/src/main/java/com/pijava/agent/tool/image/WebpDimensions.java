package com.pijava.agent.tool.image;

/**
 * WebP RIFF 头的画布尺寸解析（批 9 裁决 D1）。
 *
 * <p>JDK 没有 WebP 解码器，但限内图片只需知道宽高即可判定是否直通（pi 用 photon
 * 解码取尺寸）。按 WebP Container Specification 解析首个 {@code VP8X}/{@code VP8L}/
 * {@code VP8} chunk 的尺寸字段；解析不出 ⇒ {@code null}（上层按解码失败处理）。</p>
 *
 * <ul>
 *   <li>VP8X（扩展）：payload 偏移 7/10 起各 24 位 LE 存 宽高−1；</li>
 *   <li>VP8L（无损）：payload[0]==0x2F，自 payload[1] 打包 14+14 位 宽高−1；</li>
 *   <li>VP8（有损）：payload[0..2]==9D 01 2A，偏移 6/8 起各 16 位 LE（14 位有效）。</li>
 * </ul>
 */
final class WebpDimensions {

    private WebpDimensions() {}

    /** @return {@code {width, height}} 或 {@code null}（头不合法/缺 chunk）。 */
    static int[] dimensions(byte[] b) {
        if (b.length < 12 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
            || b[8] != 'W' || b[9] != 'E' || b[10] != 'B' || b[11] != 'P') {
            return null;
        }
        int offset = 12;
        while (offset + 8 <= b.length) {
            String fourcc = new String(b, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long chunkSize = u32le(b, offset + 4);
            int data = offset + 8;
            if (chunkSize < 0 || chunkSize > b.length - data) return null;
            switch (fourcc) {
                case "VP8X":
                    if (chunkSize < 13) return null;
                    int vw = (u24le(b, data + 7) & 0xFFFFFF) + 1;
                    int vh = (u24le(b, data + 10) & 0xFFFFFF) + 1;
                    return new int[] {vw, vh};
                case "VP8L":
                    if (chunkSize < 5 || (b[data] & 0xFF) != 0x2F) return null;
                    int bits = (int) u32le(b, data + 1);
                    return new int[] {(bits & 0x3FFF) + 1, ((bits >>> 14) & 0x3FFF) + 1};
                case "VP8 ":
                    if (chunkSize < 10
                        || (b[data] & 0xFF) != 0x9D
                        || (b[data + 1] & 0xFF) != 0x01
                        || (b[data + 2] & 0xFF) != 0x2A) {
                        return null;
                    }
                    return new int[] {(u16le(b, data + 6) & 0x3FFF), (u16le(b, data + 8) & 0x3FFF)};
                default:
                    // RIFF chunk 偶对齐填充。
                    offset = data + (int) chunkSize + ((int) chunkSize % 2);
            }
        }
        return null;
    }

    private static int u16le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int u24le(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16);
    }

    private static long u32le(byte[] b, int o) {
        return ((long) (b[o] & 0xFF)) | ((long) (b[o + 1] & 0xFF) << 8)
            | ((long) (b[o + 2] & 0xFF) << 16) | ((long) (b[o + 3] & 0xFF) << 24);
    }
}
