package com.pijava.tui.util;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.inline.InlineDisplay;
import dev.tamboui.layout.Rect;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.tui.TerminalInputReader;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.event.Event;

/**
 * Raw-scrollback inline shell, aligned with Codex's
 * {@code tui.alternate_screen = "never"} / {@code --no-alt-screen} mode.
 *
 * <p>Unlike {@code ToolkitRunner} (alternate screen + full-frame redraw), this
 * shell never enters the alternate screen and never captures the mouse:
 * transcript lines are appended to the terminal's main buffer via
 * {@link InlineDisplay#println}, so the terminal's own scrollbar/scrollback
 * scrolls the whole content area. Only a fixed bottom region (editor + status)
 * is redrawn in place.</p>
 *
 * <p>Mouse capture stays off so wheel/trackpad scrolls reach the terminal
 * natively (the double-scrollbar problem disappears). Streaming drafts are
 * rewritten in place via {@link #replaceLastBlock}; modal overlays briefly
 * switch to the alternate screen (preserving the transcript underneath) and
 * switch back, like Codex's pickers.</p>
 *
 * <p>Rendering is on demand: the loop draws once at startup, after every
 * input event, and whenever the app marks the shell dirty (streaming deltas,
 * session snapshots). It never repaints while idle, so the terminal's own
 * scrollback stays exactly where the user left it — a periodic redraw would
 * move the cursor back to the bottom and undo every scroll.</p>
 */
public final class InlineTuiShell implements AutoCloseable {

    /** Default bottom region height: one editor row + one status row. */
    public static final int DEFAULT_BOTTOM_HEIGHT = 2;

    /** Event polling timeout; doubles as the idle redraw interval. */
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(40);

    private final Backend backend;
    private final InlineDisplay display;
    private final int bottomHeight;
    private final BlockingQueue<Event> eventQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private final TerminalInputReader inputReader;

    private volatile int width;
    private volatile int contentHeight;
    private Buffer buffer;
    private Frame frame;

    private InlineDisplay overlayDisplay;
    private volatile int overlayHeight;
    private Buffer overlayBuffer;
    private Frame overlayFrame;

    /** Lines appended to the scrollback above the display area. */
    private final AtomicLong printedLines = new AtomicLong();

    private InlineTuiShell(Backend backend, InlineDisplay display, int bottomHeight)
            throws IOException {
        this.backend = backend;
        this.display = display;
        this.bottomHeight = Math.max(1, bottomHeight);
        this.contentHeight = this.bottomHeight;
        Size size = backend.size();
        this.width = Math.max(1, size.width());
        this.buffer = Buffer.empty(Rect.of(width, this.bottomHeight));
        this.frame = Frame.forTesting(buffer);
        this.inputReader = new TerminalInputReader(backend, eventQueue,
            BindingSets.defaults(), running, POLL_TIMEOUT);
        backend.onResize(this::onResize);
    }

    /** Creates a shell for the real terminal (raw mode + bracketed paste). */
    public static InlineTuiShell create() throws Exception {
        return create(DEFAULT_BOTTOM_HEIGHT);
    }

    /** Creates a shell with an explicit bottom-region height. */
    public static InlineTuiShell create(int bottomHeight) throws Exception {
        Backend backend = TamboUIAdapter.createBackend();
        backend.enableRawMode();
        backend.enableBracketedPaste();
        InlineDisplay display = InlineDisplay.withBackend(Math.max(1, bottomHeight), backend);
        return new InlineTuiShell(backend, display, bottomHeight);
    }

    /** Factory for headless tests with an injected backend. */
    public static InlineTuiShell createForTest(Backend backend) throws Exception {
        return createForTest(backend, DEFAULT_BOTTOM_HEIGHT);
    }

    static InlineTuiShell createForTest(Backend backend, int bottomHeight) throws Exception {
        backend.enableRawMode();
        backend.enableBracketedPaste();
        InlineDisplay display = InlineDisplay.withBackend(Math.max(1, bottomHeight), backend);
        return new InlineTuiShell(backend, display, bottomHeight);
    }

    private void onResize() {
        try {
            int w = backend.size().width();
            if (w > 0) {
                width = w;
            }
        } catch (IOException ignored) {
            // keep the last known width
        }
    }

    /** The current terminal width in cells. */
    public int width() {
        return width;
    }

    /** Lines appended to the scrollback so far (test hook). */
    public long printedLines() {
        return printedLines.get();
    }

    /** Sets the height of the managed bottom region (editor + status rows). */
    public void setContentHeight(int height) {
        int h = Math.max(1, height);
        if (h != contentHeight || buffer.area().height() != h) {
            contentHeight = h;
            buffer = Buffer.empty(Rect.of(width, contentHeight));
            frame = Frame.forTesting(buffer);
        }
    }

    /** Appends one plain line to the scrollback above the display area. */
    public void println(String line) {
        display.println(line == null ? "" : line);
        printedLines.incrementAndGet();
    }

    /** Appends one styled line to the scrollback above the display area. */
    public void println(Line line) {
        if (line == null) {
            println("");
            return;
        }
        display.println(toText(line));
        printedLines.incrementAndGet();
    }

    /**
     * Rewrites the last printed block (the streaming draft) in place.
     *
     * @param lineCount the current height of the block in terminal rows
     * @param block     the new block content (may be shorter than
     *                  {@code lineCount}; extra rows are erased)
     * @return {@code false} when rewriting is unsafe (the block is taller than
     *         the space above the bottom region and would corrupt older
     *         output); the caller then stops rewriting and lets the final
     *         message append normally on TextEnd
     */
    public boolean replaceLastBlock(int lineCount, List<Line> block) {
        if (lineCount <= 0 || block == null || printedLines.get() < lineCount) {
            return false;
        }
        try {
            int screen = terminalHeight();
            if (lineCount > screen - bottomHeight) {
                return false;
            }
            backend.carriageReturn();
            backend.moveCursorUp(lineCount);
            for (int i = 0; i < lineCount; i++) {
                backend.carriageReturn();
                backend.eraseToEndOfLine();
                if (i < block.size() && block.get(i) != null) {
                    backend.writeRaw(toAnsi(block.get(i)));
                }
                if (i < lineCount - 1) {
                    backend.writeRaw("\n");
                }
            }
            // Land back on display line 0 for the next in-place redraw.
            backend.writeRaw("\n");
            backend.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Switches to the alternate screen for a full-screen modal overlay. The
     * main buffer (transcript + bottom region) is preserved underneath and
     * restored by {@link #endOverlay()}.
     */
    public void beginOverlay() throws IOException {
        if (overlayDisplay != null) {
            return;
        }
        backend.enterAlternateScreen();
        backend.clear();
        overlayHeight = terminalHeight();
        overlayDisplay = InlineDisplay.withBackend(Math.max(1, overlayHeight), backend);
        overlayBuffer = Buffer.empty(Rect.of(width, overlayHeight));
        overlayFrame = Frame.forTesting(overlayBuffer);
    }

    /** Whether a full-screen overlay is currently active. */
    public boolean overlayActive() {
        return overlayDisplay != null;
    }

    /** Leaves the overlay alternate screen and restores the main buffer. */
    public void endOverlay() throws IOException {
        if (overlayDisplay == null) {
            return;
        }
        overlayDisplay.release();
        backend.leaveAlternateScreen();
        overlayDisplay = null;
        overlayBuffer = null;
        overlayFrame = null;
    }

    /**
     * Runs the inline event loop. The renderer is invoked once initially,
     * after every event, and whenever {@link #markDirty()} was called
     * (async streaming updates); idle loops sleep without touching the
     * terminal, keeping the native scrollback position intact.
     *
     * @param handler  receives parsed terminal events (keys, paste, resize)
     * @param renderer renders one frame (bottom region or active overlay)
     */
    public void run(Consumer<Event> handler, Consumer<Frame> renderer) throws Exception {
        inputReader.start();
        try {
            render(renderer);
            while (running.get()) {
                Event event = eventQueue.poll(POLL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (event != null && running.get()) {
                    // Clear before the handler so dispatches made during it
                    // re-mark the shell and render on the next iteration.
                    dirty.set(false);
                    handler.accept(event);
                    render(renderer);
                } else if (running.get() && dirty.getAndSet(false)) {
                    render(renderer);
                }
            }
        } finally {
            inputReader.stop(POLL_TIMEOUT.toMillis() * 2);
        }
    }

    /** Stops the event loop (idempotent). */
    public void quit() {
        running.set(false);
    }

    /** Requests a redraw on the next loop iteration (async streaming updates). */
    public void markDirty() {
        dirty.set(true);
    }

    private void render(Consumer<Frame> renderer) {
        if (overlayDisplay != null) {
            // The app renderer owns overlay lifecycle (begin/end); calling
            // renderOverlay here would re-enter it and race with its
            // endOverlay cleanup (overlayFrame cleared mid-render).
            renderer.accept(frame);
            return;
        }
        syncWidth();
        buffer.clear();
        frame.clearCursor();
        renderer.accept(frame);
        int cursorX = frame.cursorPosition().map(p -> p.x()).orElse(-1);
        int cursorY = frame.cursorPosition().map(p -> p.y()).orElse(-1);
        display.render((area, buf) -> copy(buffer, area, buf), contentHeight, cursorX, cursorY);
    }

    /** Renders the overlay frame through the given renderer (no-op when inactive). */
    public void renderOverlay(Consumer<Frame> renderer) {
        var display = overlayDisplay;
        var frame = overlayFrame;
        var buffer = overlayBuffer;
        if (display == null || frame == null || buffer == null) {
            return;
        }
        syncWidth();
        if (buffer.area().width() != width
                || buffer.area().height() != overlayHeight) {
            buffer = Buffer.empty(Rect.of(width, overlayHeight));
            frame = Frame.forTesting(buffer);
            overlayBuffer = buffer;
            overlayFrame = frame;
        }
        final var renderBuffer = buffer;
        buffer.clear();
        frame.clearCursor();
        renderer.accept(frame);
        int cursorX = frame.cursorPosition().map(p -> p.x()).orElse(-1);
        int cursorY = frame.cursorPosition().map(p -> p.y()).orElse(-1);
        display.render((area, buf) -> copy(renderBuffer, area, buf),
            overlayHeight, cursorX, cursorY);
    }

    private void syncWidth() {
        try {
            int w = backend.size().width();
            if (w > 0 && w != width) {
                width = w;
            }
        } catch (IOException ignored) {
            // keep the last known width
        }
    }

    private int terminalHeight() {
        try {
            int h = backend.size().height();
            return h > 0 ? h : 40;
        } catch (IOException e) {
            return 40;
        }
    }

    private dev.tamboui.text.Text toText(Line line) {
        return dev.tamboui.text.Text.from(line);
    }

    private String toAnsi(Line line) {
        Buffer one = Buffer.empty(Rect.of(width, 1));
        one.setLine(0, 0, line);
        return one.toAnsiStringTrimmed();
    }

    private static void copy(Buffer from, Rect area, Buffer to) {
        int h = Math.min(from.area().height(), area.height());
        int w = Math.min(from.area().width(), area.width());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                to.set(x, y, from.get(x, y));
            }
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        inputReader.stop(POLL_TIMEOUT.toMillis() * 2);
        try {
            if (overlayDisplay != null) {
                overlayDisplay.release();
                backend.leaveAlternateScreen();
                overlayDisplay = null;
            }
            if (display != null) {
                display.release();
            }
        } catch (IOException ignored) {
            // best-effort restore
        }
        try {
            backend.disableBracketedPaste();
        } catch (IOException ignored) {
            // best-effort
        }
        try {
            backend.disableRawMode();
        } catch (IOException ignored) {
            // best-effort
        }
        backend.close();
    }
}
