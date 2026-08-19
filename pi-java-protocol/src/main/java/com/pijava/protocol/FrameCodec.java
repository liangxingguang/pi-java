package com.pijava.protocol;

import java.io.ByteArrayOutputStream;

/**
 * 4 字节大端无符号长度前缀 + 载荷（对齐 pi {@code framing.ts}）。
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    /** 编码：{@code [len(4B BE)][payload]}。 */
    public static byte[] encode(byte[] payload) {
        var out = new ByteArrayOutputStream(payload.length + 4);
        out.write((payload.length >>> 24) & 0xff);
        out.write((payload.length >>> 16) & 0xff);
        out.write((payload.length >>> 8) & 0xff);
        out.write(payload.length & 0xff);
        out.writeBytes(payload);
        return out.toByteArray();
    }
}
