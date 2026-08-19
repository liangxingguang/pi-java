package com.pijava.coding.agent.rpc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 单行 JSON 序列化（一行一个 JSON 对象，LF 结尾，逐行 flush）。
 *
 * <p>写操作同步：RPC 模式下事件由 run 的虚拟线程写出、响应由主线程写出，
 * 两个线程可能并发写同一 stdout，需串行化避免字节交错。</p>
 */
public final class JsonlWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutputStream out;

    /** @param out output stream (each write flushes) */
    public JsonlWriter(OutputStream out) {
        this.out = out;
    }

    /** 把对象序列化为单行 JSON 写出。 */
    public synchronized void write(Object value) throws IOException {
        JSON.writeValue(out, value);
        out.write('\n');
        out.flush();
    }

    /** 写出已序列化的 JSON 行（原样，不重新编码）。 */
    public synchronized void writeLine(String jsonLine) throws IOException {
        out.write(jsonLine.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }
}
