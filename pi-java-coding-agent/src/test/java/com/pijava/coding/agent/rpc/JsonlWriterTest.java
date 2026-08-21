package com.pijava.coding.agent.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsonlWriter —— 多行输出 + 不关闭目标流（回归：writeValue(OutputStream)
 * 的 AUTO_CLOSE_TARGET 会在首次写时 close 掉 System.out）。
 */
class JsonlWriterTest {

    @Test
    void writesMultipleLines() throws Exception {
        var sink = new ByteArrayOutputStream();
        var writer = new JsonlWriter(sink);
        writer.write(RpcResponse.ok("1", "a"));
        writer.write(RpcResponse.ok("2", "b"));
        writer.write(RpcResponse.ok("3", "c"));
        String out = sink.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("\"id\":\"1\"").contains("\"id\":\"2\"")
            .contains("\"id\":\"3\"");
        // 每行 LF 结尾
        assertThat(out.lines().count()).isEqualTo(3);
    }

    @Test
    void doesNotCloseTheUnderlyingStream() throws Exception {
        var sink = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                throw new AssertionError("JsonlWriter must not close the output stream");
            }
        };
        var writer = new JsonlWriter(sink);
        writer.write(RpcResponse.ok("1", "a"));
        writer.write(RpcResponse.ok("2", "b"));
    }
}
