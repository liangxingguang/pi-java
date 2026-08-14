package com.pijava.tui.app;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;

/**
 * In-memory terminal backend for headless integration tests: feeds byte
 * sequences through the real TamboUI event parser and records draw calls.
 */
final class FakeBackend implements Backend {

    private final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();
    private int drawCount;

    /** Queue raw terminal bytes (UTF-8) as if typed by the user. */
    void feed(String text) {
        for (char c : text.toCharArray()) {
            input.add((int) c);
        }
    }

    /** Number of frames drawn so far. */
    synchronized int drawCount() {
        return drawCount;
    }

    @Override
    public void draw(DiffResult diffResult) throws IOException {
        synchronized (this) {
            drawCount++;
        }
    }

    @Override
    public void flush() throws IOException {
        // no-op
    }

    @Override
    public void clear() throws IOException {
        // no-op
    }

    @Override
    public Size size() throws IOException {
        return new Size(100, 30);
    }

    @Override
    public void showCursor() throws IOException {
        // no-op
    }

    @Override
    public void hideCursor() throws IOException {
        // no-op
    }

    @Override
    public Position getCursorPosition() throws IOException {
        return Position.ORIGIN;
    }

    @Override
    public void setCursorPosition(Position position) throws IOException {
        // no-op
    }

    @Override
    public void enterAlternateScreen() throws IOException {
        // no-op
    }

    @Override
    public void leaveAlternateScreen() throws IOException {
        // no-op
    }

    @Override
    public void enableRawMode() throws IOException {
        // no-op
    }

    @Override
    public void disableRawMode() throws IOException {
        // no-op
    }

    @Override
    public void onResize(Runnable callback) {
        // no-op
    }

    @Override
    public int read(int timeoutMillis) throws IOException {
        try {
            var value = input.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            return value == null ? -1 : value;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public int peek(int timeoutMillis) throws IOException {
        var value = input.peek();
        return value == null ? -1 : value;
    }

    @Override
    public void close() throws IOException {
        input.clear();
    }
}
