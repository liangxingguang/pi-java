package com.pijava.tui.app;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import dev.tamboui.buffer.Cell;
import dev.tamboui.buffer.DiffResult;
import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.style.Color;
import dev.tamboui.terminal.Backend;

/**
 * In-memory terminal backend for headless integration tests: feeds byte
 * sequences through the real TamboUI event parser and records draw calls.
 */
final class FakeBackend implements Backend {

    private final BlockingQueue<Integer> input = new LinkedBlockingQueue<>();
    private int drawCount;
    private final char[][] grid = new char[60][160];
    private char[][] lastGrid = new char[60][160];
    private final Set<Long> cursorCells = new HashSet<>();
    private final Set<Long> backgroundCells = new HashSet<>();
    private final java.util.List<String> rawWrites =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Queue raw terminal bytes (UTF-8) as if typed by the user. */
    void feed(String text) {
        for (char c : text.toCharArray()) {
            input.add((int) c);
        }
    }

    /** Raw bytes written to the terminal (scrollback lines, escape sequences). */
    java.util.List<String> rawWrites() {
        return rawWrites;
    }

    /**
     * Records raw output so inline-mode tests can assert what was appended to
     * the terminal scrollback (InlineDisplay writes through this channel).
     */
    @Override
    public void writeRaw(byte[] data) throws IOException {
        rawWrites.add(new String(data, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Number of frames drawn so far. */
    synchronized int drawCount() {
        return drawCount;
    }

    @Override
    public void draw(DiffResult diffResult) throws IOException {
        synchronized (this) {
            drawCount++;
            for (int i = 0; i < diffResult.size(); i++) {
                int x = diffResult.getX(i);
                int y = diffResult.getY(i);
                if (x < 0 || x >= 160 || y < 0 || y >= 60) {
                    continue;
                }
                Cell cell = diffResult.getCell(i);
                var symbol = cell.symbol();
                grid[y][x] = symbol == null || symbol.isEmpty() ? ' ' : symbol.charAt(0);
                if (cell.style().bg().isPresent()
                        && isCursorShade(cell.style().bg().get())) {
                    cursorCells.add(((long) y << 32) | (x & 0xFFFFFFFFL));
                }
                if (cell.style().bg().isPresent()) {
                    backgroundCells.add(((long) y << 32) | (x & 0xFFFFFFFFL));
                }
            }
            // Snapshot the latest frame so tests can assert what is currently
            // visible (the accumulated grid would hide scroll offsets).
            for (int y = 0; y < lastGrid.length; y++) {
                System.arraycopy(grid[y], 0, lastGrid[y], 0, lastGrid[y].length);
            }
        }
    }

    /** Whether any rendered line contains the given text (accumulated). */
    synchronized boolean hasLineContaining(String text) {
        for (char[] row : grid) {
            if (new String(row).contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** The last drawn frame, as trimmed strings per row. */
    synchronized java.util.List<String> lastDrawLines() {
        var lines = new java.util.ArrayList<String>();
        for (char[] row : lastGrid) {
            lines.add(new String(row).stripTrailing());
        }
        return lines;
    }

    /** Whether any rendered cell carries the cyan cursor block. */
    synchronized boolean hasCursorCell() {
        return !cursorCells.isEmpty();
    }

    /** Whether any rendered cell has an explicit background (theme active). */
    synchronized boolean hasBackgroundCells() {
        return !backgroundCells.isEmpty();
    }

    private static boolean isCursorShade(Color color) {
        return com.pijava.tui.util.EditorElement.CURSOR.equals(color);
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
        // A real terminal blocks until the next byte of an escape sequence
        // arrives; the queue-backed fake must do the same, otherwise the
        // first ESC of a burst is reported as a standalone key and the
        // remaining bytes are misparsed as characters.
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
        while (true) {
            var value = input.peek();
            if (value != null) {
                return value;
            }
            if (System.currentTimeMillis() >= deadline) {
                return -1;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    @Override
    public void close() throws IOException {
        input.clear();
    }
}
