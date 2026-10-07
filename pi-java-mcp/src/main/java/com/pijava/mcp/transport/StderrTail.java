package com.pijava.mcp.transport;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Tail buffer of a child process's stderr (stdio.ts:63-67,143-157): keeps the
 * last {@link #TAIL_BYTES}.
 */
final class StderrTail {

    /** Bytes retained. */
    static final int TAIL_BYTES = 64 * 1024;

    private byte[] buffer = new byte[0];

    /** Append a chunk, dropping earlier bytes beyond the tail size. */
    synchronized void append(byte[] chunk) {
        if (chunk.length >= TAIL_BYTES) {
            buffer = Arrays.copyOfRange(chunk, chunk.length - TAIL_BYTES, chunk.length);
            return;
        }
        if (buffer.length + chunk.length <= TAIL_BYTES) {
            var next = new byte[buffer.length + chunk.length];
            System.arraycopy(buffer, 0, next, 0, buffer.length);
            System.arraycopy(chunk, 0, next, buffer.length, chunk.length);
            buffer = next;
            return;
        }
        var keep = TAIL_BYTES - chunk.length;
        var next = new byte[TAIL_BYTES];
        System.arraycopy(buffer, buffer.length - keep, next, 0, keep);
        System.arraycopy(chunk, 0, next, keep, chunk.length);
        buffer = next;
    }

    /** Decode the tail as UTF-8. */
    synchronized String text() {
        return new String(buffer, StandardCharsets.UTF_8);
    }
}
