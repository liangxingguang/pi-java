package com.pijava.tui.util;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import dev.tamboui.backend.jline3.JLineBackend;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

/**
 * JLine backend that enters raw mode without the Mode 2027 (grapheme
 * cluster) handshake.
 *
 * <p>On Windows the stock Panama backend reads console input records and
 * drops every key whose {@code uChar} is 0 — that includes all arrow/function
 * keys — so navigation never reaches the app. JLine reads the console
 * correctly and delivers arrows as ANSI sequences, so pi-java switches to it
 * on Windows. The Mode 2027 DECRQM/DECRPM handshake (whose late ConPTY
 * response leaked {@code 2027;3$y} into the editor) is skipped here exactly
 * as in {@link NoMode2027Backend}.</p>
 *
 * <p>Also makes the mouse wheel work on Windows consoles. TamboUI's stock
 * {@code enableMouseCapture()} only writes raw CSI tracking sequences without
 * telling JLine, so JLine's Windows terminal never sets
 * {@code ENABLE_MOUSE_INPUT} and drops {@code MOUSE_EVENT_RECORD}s (the
 * conhost-style delivery path). This variant also calls
 * {@link Terminal#trackMouse} so records flow, then translates JLine's legacy
 * X10 mouse sequences ({@code ESC [ M b x y}) into the SGR format
 * ({@code ESC [ < b ; x ; y M}) that TamboUI's {@code EventParser}
 * understands. SGR sequences delivered directly by Windows Terminal /
 * ConPTY pass through untouched.</p>
 */
public final class NoMode2027JLineBackend extends JLineBackend {

    /** The ESC byte. */
    private static final int ESC = 27;
    /** Lookahead timeout for escape-sequence components. */
    private static final int READ_TIMEOUT_MS = 50;

    private Attributes savedAttributes;
    private final Deque<Integer> pending = new ArrayDeque<>();

    /**
     * Creates the JLine backend (no Mode 2027 handshake).
     *
     * @throws IOException if the JLine terminal cannot be created
     */
    public NoMode2027JLineBackend() throws IOException {
        super();
    }

    @Override
    public void enableRawMode() throws IOException {
        var terminal = jlineTerminal();
        savedAttributes = terminal.enterRawMode();
        var attrs = terminal.getAttributes();
        attrs.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attrs);
    }

    @Override
    public void disableRawMode() throws IOException {
        if (savedAttributes != null) {
            jlineTerminal().setAttributes(savedAttributes);
            savedAttributes = null;
        }
    }

    @Override
    public void enableMouseCapture() throws IOException {
        // Any-event tracking: the console must also forward motion WITHOUT a
        // button held, so the chat viewport can thicken its scrollbar under
        // the cursor (Codex-CLI style). Normal tracking drops those records.
        jlineTerminal().trackMouse(Terminal.MouseTracking.Any);
        // Then write the raw CSI sequences with SGR (1006) last, so terminals
        // that honor escape requests keep sending SGR rather than urxvt/X10.
        super.enableMouseCapture();
        // SGR any-event tracking (1003): hover moves for terminals that
        // deliver SGR directly (Windows Terminal / ConPTY passthrough).
        var writer = jlineTerminal().writer();
        writer.print("\u001b[?1003h");
        writer.flush();
    }

    @Override
    public void disableMouseCapture() throws IOException {
        super.disableMouseCapture();
        var writer = jlineTerminal().writer();
        writer.print("\u001b[?1003l");
        writer.flush();
        jlineTerminal().trackMouse(Terminal.MouseTracking.Off);
    }

    @Override
    public int read(int timeoutMs) throws IOException {
        if (!pending.isEmpty()) {
            return pending.poll();
        }
        int raw = super.read(timeoutMs);
        return raw < 0 ? raw : translate(raw, this::rawRead, this::rawPeek, pending);
    }

    @Override
    public int peek(int timeoutMs) throws IOException {
        if (!pending.isEmpty()) {
            return pending.peek();
        }
        return super.peek(timeoutMs);
    }

    private int rawRead() throws IOException {
        return jlineTerminal().reader().read(READ_TIMEOUT_MS);
    }

    private int rawPeek() throws IOException {
        return jlineTerminal().reader().peek(READ_TIMEOUT_MS);
    }

    /**
     * X10 → SGR conversion core (package-private for unit tests).
     *
     * <p>When {@code raw} is ESC followed by {@code [ M b x y}, consumes the
     * sequence from {@code read}/{@code peek}, queues the SGR equivalent in
     * {@code out} and returns ESC. Otherwise returns {@code raw} unchanged,
     * queuing any already-consumed lookahead bytes in {@code out}.</p>
     */
    static int translate(int raw, ThrowingIntSupplier read, ThrowingIntSupplier peek,
                         Deque<Integer> out) throws IOException {
        if (raw != ESC) {
            return raw;
        }
        int bracket = peek.get();
        if (bracket != '[') {
            return raw;
        }
        read.get(); // consume '['
        int marker = peek.get();
        if (marker != 'M') {
            out.add((int) '[');
            return raw;
        }
        read.get(); // consume 'M'
        int b = read.get();
        int x = read.get();
        int y = read.get();
        if (b < 0 || x < 0 || y < 0) {
            // Incomplete sequence (timeout/EOF): push back what was consumed
            // so the parser still sees a standalone ESC plus the remainder.
            out.add((int) '[');
            out.add((int) 'M');
            if (b >= 0) out.add(b);
            if (x >= 0) out.add(x);
            return raw;
        }
        // Re-queue the CSI introducer consumed above: the caller returns ESC
        // and the EventParser expects the full SGR shape ESC [ < b ; x ; y M;
        // without the '[', the body would be parsed as standalone characters
        // and typed into the editor instead of scrolling the viewport.
        out.add((int) '[');
        enqueueSgr(out, b - 32, x - 32, y - 32);
        return raw;
    }

    /** Queue the SGR body {@code < b ; x ; y M} (the caller re-adds {@code [}). */
    private static void enqueueSgr(Deque<Integer> out, int button, int x, int y) {
        out.add((int) '<');
        for (var ch : String.valueOf(button).toCharArray()) out.add((int) ch);
        out.add((int) ';');
        for (var ch : String.valueOf(x).toCharArray()) out.add((int) ch);
        out.add((int) ';');
        for (var ch : String.valueOf(y).toCharArray()) out.add((int) ch);
        out.add((int) 'M');
    }

    /** Like {@link java.util.function.IntSupplier} but allows I/O errors. */
    @FunctionalInterface
    interface ThrowingIntSupplier {
        int get() throws IOException;
    }
}
