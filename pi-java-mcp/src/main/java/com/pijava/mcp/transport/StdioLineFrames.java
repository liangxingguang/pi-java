package com.pijava.mcp.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Byte-oriented newline frame reader (stdio.ts:160-178).
 *
 * <p>Reads one byte at a time and splits on {@code \n}; unlike
 * {@code BufferedReader.readLine}, no assumption about chunk boundaries is
 * made, so a multi-byte character cannot be cut between reads.</p>
 */
final class StdioLineFrames {

    private StdioLineFrames() {
    }

    /** Pump frames from a stream until it ends. */
    static void pump(InputStream stream, int maxMessageBytes,
                     Consumer<byte[]> onLine, Consumer<Throwable> onError) {
        var buffer = new ByteArrayOutputStream();
        try {
            int read;
            while ((read = stream.read()) != -1) {
                if (read == '\n') {
                    emit(buffer, onLine);
                    continue;
                }
                if (buffer.size() + 1 > maxMessageBytes) {
                    onError.accept(new IOException(
                            "MCP message exceeds " + maxMessageBytes + " bytes"));
                    return;
                }
                buffer.write(read);
            }
            if (buffer.size() > 0) {
                onError.accept(new IOException(
                        "MCP server closed with an incomplete JSON-RPC message"));
            }
        } catch (IOException error) {
            onError.accept(error);
        }
    }

    private static void emit(ByteArrayOutputStream buffer, Consumer<byte[]> onLine) {
        var bytes = buffer.toByteArray();
        buffer.reset();
        var length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        var frame = new byte[length];
        System.arraycopy(bytes, 0, frame, 0, length);
        onLine.accept(frame);
    }
}
