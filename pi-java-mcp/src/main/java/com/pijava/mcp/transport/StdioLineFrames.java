package com.pijava.mcp.transport;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Byte-oriented newline frame reader for MCP stdio (pi {@code transports/lines.ts}).
 *
 * <p>按块扫描 {@code \n}：未完成的尾巴**按块**留着，换行到达时才 join 一次 —— 与 pi 的
 * {@code JsonRpcLineReader} 同式。旧的逐字节实现语义相同，但一条 MB 级消息要逐字节走一遍循环；
 * 这里与 pi 一样「每块只扫描一次、只在拼帧时复制一次」。</p>
 *
 * <p>⚠️ 刻意不用 {@code BufferedReader.readLine()}：那是字符流，会按编码解码后切行，
 * 一条消息里被拆开的代理对/多字节字符可能被改写；MCP 的帧是**字节**面协议。</p>
 */
final class StdioLineFrames {

    private static final int CHUNK_BYTES = 8 * 1024;

    private StdioLineFrames() {
    }

    /** Pump frames from a stream until it ends. */
    static void pump(InputStream stream, int maxMessageBytes,
                     Consumer<byte[]> onLine, Consumer<Throwable> onError) {
        var chunk = new byte[CHUNK_BYTES];
        // 尚未收到换行的那条消息：按块留着，换行时一次性 join（pi 的 stdoutChunks）。
        var pending = new ArrayList<byte[]>();
        long pendingBytes = 0;
        try {
            int read;
            while ((read = stream.read(chunk)) != -1) {
                int start = 0;
                while (true) {
                    int newline = indexOf(chunk, start, read);
                    if (newline < 0) {
                        if (start < read) {
                            var rest = Arrays.copyOfRange(chunk, start, read);
                            pending.add(rest);
                            pendingBytes += rest.length;
                            if (pendingBytes > maxMessageBytes) {
                                onError.accept(oversized(maxMessageBytes));
                                return;
                            }
                        }
                        break;
                    }
                    var frame = join(pending, pendingBytes, chunk, start, newline);
                    pending.clear();
                    pendingBytes = 0;
                    onLine.accept(frame);
                    start = newline + 1;
                }
            }
            if (pendingBytes > 0) {
                onError.accept(new IOException(
                        "MCP server closed with an incomplete JSON-RPC message"));
            }
        } catch (IOException error) {
            onError.accept(error);
        }
    }

    /** 下一处 {@code \n} 的下标，没有则 {@code -1}。 */
    private static int indexOf(byte[] chunk, int from, int to) {
        for (int i = from; i < to; i++) {
            if (chunk[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * 拼出一帧：已攒的块 ＋ 本块中 {@code [start, end)} 段；末尾 {@code \r} 去掉（CRLF 服务器）。
     *
     * <p>与旧实现一致：空行也照发（上层会以「不是合法 JSON」报错），不静默吞掉。</p>
     */
    private static byte[] join(List<byte[]> pending, long pendingBytes,
                               byte[] chunk, int start, int end) {
        int tail = end - start;
        var frame = new byte[(int) pendingBytes + tail];
        int at = 0;
        for (var part : pending) {
            System.arraycopy(part, 0, frame, at, part.length);
            at += part.length;
        }
        System.arraycopy(chunk, start, frame, at, tail);
        int length = frame.length;
        if (length > 0 && frame[length - 1] == '\r') {
            length--;
        }
        return length == frame.length ? frame : Arrays.copyOf(frame, length);
    }

    private static IOException oversized(int maxMessageBytes) {
        return new IOException("MCP message exceeds " + maxMessageBytes + " bytes");
    }
}
