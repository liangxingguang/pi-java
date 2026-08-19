package com.pijava.coding.agent.rpc;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 严格 LF-only JSONL 分帧（对齐 pi {@code jsonl.ts}）。
 *
 * <p>不能用 {@code BufferedReader.readLine()}：它在 {@code \r}、{@code \n}、
 * {@code \r\n} 上都切分，而 JSONL 只在 {@code \n} 上分帧、仅剥除行尾 {@code \r}。
 * 按字节扫描 {@code \n}（0x0A），因此 JSON 字符串内合法的 U+2028/U+2029
 * （多字节 UTF-8，不含 0x0A 字节）不会被误切。EOF 时残留缓冲作为最后一行；
 * 空行跳过。</p>
 */
public final class JsonlReader implements Closeable {

    private final InputStream in;
    private final byte[] buf = new byte[8192];
    private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
    private int pos;
    private int end;

    /** @param in raw input stream (wrapped in BufferedInputStream) */
    public JsonlReader(InputStream in) {
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in);
    }

    /**
     * 读下一行；EOF 返回 null。仅按 {@code \n} 切分，剥除行尾 {@code \r}。
     *
     * @throws IOException on I/O error
     */
    public String readLine() throws IOException {
        while (true) {
            int c = readByte();
            if (c == -1) {
                return drainLine();
            }
            if (c == '\n') {
                String line = drainLine();
                if (line != null) {
                    return line;
                }
                // 空行 → 继续读下一行
                continue;
            }
            lineBuf.write(c);
        }
    }

    private int readByte() throws IOException {
        if (pos >= end) {
            end = in.read(buf);
            pos = 0;
            if (end <= 0) {
                return -1;
            }
        }
        return buf[pos++] & 0xff;
    }

    /** 把当前行缓冲取出；空行/仅 \r 返回 null。 */
    private String drainLine() {
        byte[] bytes = lineBuf.toByteArray();
        lineBuf.reset();
        if (bytes.length == 0) {
            return null;
        }
        int len = bytes.length;
        if (bytes[len - 1] == '\r') {
            len--;
        }
        if (len == 0) {
            return null;
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
