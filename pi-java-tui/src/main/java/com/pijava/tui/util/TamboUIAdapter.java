package com.pijava.tui.util;

import java.util.Collection;
import java.io.IOException;
import java.time.Duration;

import com.pijava.coding.agent.core.KeybindingsManager;

import dev.tamboui.terminal.Backend;
import dev.tamboui.layout.Constraint;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.MarkupTextElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.Spacer;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.elements.TextAreaElement;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;
import dev.tamboui.widgets.scrollbar.Scrollbar;
import dev.tamboui.widgets.scrollbar.ScrollbarOrientation;
import dev.tamboui.widgets.scrollbar.ScrollbarState;

/**
 * Isolation layer for direct TamboUI API usage (Phase 3 design §2.2, risk R1).
 *
 * <p>All TamboUI imports outside this package should be limited to event
 * types consumed by screens; business components build their widget trees
 * through the factories here so a TamboUI upgrade only touches this file.</p>
 */
public final class TamboUIAdapter {

    private TamboUIAdapter() {}

    // ── Runner / config ──────────────────────────────────────

    /** Create a full-screen TUI runner with a 50ms tick (streaming animation). */
    public static ToolkitRunner createRunner() throws Exception {
        return ToolkitRunner.create(TuiConfig.builder()
            .backend(createBackend())
            // 33ms (~30Hz) keeps continuous motion — trackpad scroll and the
            // streaming draft — visibly smooth; the diff renderer keeps the
            // cost low. The normalizer flushes fractional rows every tick.
            .tickRate(Duration.ofMillis(33))
            .alternateScreen(true)
            .hideCursor(true)
            .bracketedPaste(true)
            // Scroll input is normalized by the app shell before element
            // routing; capture stays enabled (disable_mouse_capture defaults
            // to false in the alignment design).
            .mouseCapture(true)
            .build());
    }

    /**
     * Windows console input records report arrow keys with {@code uChar == 0},
     * which the Panama backend discards; JLine reads the console correctly, so
     * Windows uses it. Other platforms keep the native Panama backend. Both
     * variants skip the Mode 2027 handshake.
     */
    static Backend createBackend() throws IOException {
        if (isWindows()) {
            return new NoMode2027JLineBackend();
        }
        return new NoMode2027Backend();
    }

    /** Whether the app runs on Windows (ConPTY/console mouse path). */
    public static boolean isWindows() {
        var os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    // ── Element factories ────────────────────────────────────

    public static TextElement text(String content) {
        return Toolkit.text(content);
    }

    public static MarkupTextElement markupText(String markup) {
        return Toolkit.markupText(markup);
    }

    public static Panel panel(dev.tamboui.toolkit.element.Element... children) {
        return Toolkit.panel(children);
    }

    public static Panel panel(String title,
                              dev.tamboui.toolkit.element.Element... children) {
        return Toolkit.panel(title, children);
    }

    public static Row row(dev.tamboui.toolkit.element.Element... children) {
        return Toolkit.row(children);
    }

    public static Column column(dev.tamboui.toolkit.element.Element... children) {
        return Toolkit.column(children);
    }

    public static Column column(Collection<? extends dev.tamboui.toolkit.element.Element> children) {
        return Toolkit.column(children.toArray(dev.tamboui.toolkit.element.Element[]::new));
    }

    public static Spacer spacer() {
        return Toolkit.spacer();
    }

    /** A fill-weight spacer (pushes siblings apart). */
    public static Spacer spacerFill() {
        return Spacer.fill();
    }

    public static dev.tamboui.toolkit.elements.ListElement<?> list(Collection<String> items) {
        return Toolkit.list(items.stream().toList());
    }

    public static TextAreaElement textArea(TextAreaState state) {
        return Toolkit.textArea(state);
    }

    /** An input row that renders text/cursor but never consumes keys. */
    public static EditorElement editorElement(TextAreaState state) {
        return new EditorElement(state);
    }

    /** A fixed-height empty row (used for a zero-height draft slot). */
    public static Spacer spacer(int length) {
        return Spacer.length(length);
    }

    // ── Scrollbar (row-level viewport) ───────────────────────

    /**
     * Builds a right-aligned vertical scrollbar with the given thumb/track
     * styles (already resolved from CSS by the caller).
     *
     * @param thumbStyle the thumb style (may be {@link Style#EMPTY})
     * @param trackStyle the track style (may be {@link Style#EMPTY})
     * @return a vertical scrollbar widget
     */
    public static Scrollbar verticalScrollbar(Style thumbStyle, Style trackStyle) {
        return Scrollbar.builder()
            .orientation(ScrollbarOrientation.VERTICAL_RIGHT)
            .thumbStyle(thumbStyle)
            .trackStyle(trackStyle)
            .build();
    }

    /**
     * Creates the state for a row-level scrollbar.
     *
     * @param contentLength          total row count
     * @param viewportContentLength  visible row count
     * @param position               first visible row index
     * @return the scrollbar state
     */
    public static ScrollbarState scrollbarState(int contentLength,
                                                int viewportContentLength,
                                                int position) {
        return new ScrollbarState(Math.max(1, contentLength))
            .viewportContentLength(viewportContentLength)
            .position(position);
    }

    // ── Layout constraints / colors ──────────────────────────

    public static Constraint fill() {
        return Toolkit.fill();
    }

    public static Constraint length(int cells) {
        return Toolkit.length(cells);
    }

    public static Color hex(String hex) {
        return Color.hex(hex);
    }

    // ── Key conversion (KeyEvent → neutral KeyStroke) ────────

    /** Convert a TamboUI {@link KeyEvent} to the coding-agent neutral stroke. */
    public static KeybindingsManager.KeyStroke toStroke(KeyEvent event) {
        var key = switch (event.code()) {
            case KeyCode.ESCAPE -> "esc";
            case KeyCode.ENTER -> "enter";
            case KeyCode.TAB -> "tab";
            case KeyCode.BACKSPACE -> "backspace";
            case KeyCode.DELETE -> "delete";
            case KeyCode.UP -> "up";
            case KeyCode.DOWN -> "down";
            case KeyCode.LEFT -> "left";
            case KeyCode.RIGHT -> "right";
            case KeyCode.HOME -> "home";
            case KeyCode.END -> "end";
            case KeyCode.PAGE_UP -> "pageup";
            case KeyCode.PAGE_DOWN -> "pagedown";
            case KeyCode.CHAR -> {
                var text = event.string();
                if (text == null || text.isBlank()) {
                    yield "unknown";
                }
                // Terminals often send Ctrl+letter as a control code (Ctrl+C = 3,
                // Ctrl+D = 4, …). Map 1–26 back to the letter so the app.*
                // bindings resolve.
                var codePoint = event.codePoint();
                if (event.hasCtrl() && codePoint >= 1 && codePoint <= 26) {
                    yield String.valueOf((char) ('a' + codePoint - 1));
                }
                yield String.valueOf(Character.toLowerCase(text.charAt(0)));
            }
            default -> "unknown";
        };
        return new KeybindingsManager.KeyStroke(
            key, event.hasCtrl(), event.hasAlt(), event.hasShift());
    }

    /**
     * Whether the event submits the editor: a plain Enter (CR) with no
     * modifiers, matching Codex CLI's composer default {@code submit=[Enter]}.
     * Shift/Alt/Ctrl variants never submit (Shift/Alt+Enter insert a newline).
     */
    public static boolean isSendEnter(KeyEvent event) {
        if (event.hasCtrl() || event.hasAlt() || event.hasShift()) {
            return false;
        }
        if (event.isKey(KeyCode.ENTER)) {
            return true;
        }
        // Windows consoles can deliver Enter as CHAR('\r') rather than ENTER.
        return event.code() == KeyCode.CHAR && "\r".equals(event.string());
    }

    /**
     * Whether the event inserts a newline in the editor:
     * <ul>
     *   <li>CHAR LF (U+000A) — Shift+Enter-as-LF / Ctrl+J fallback. Codex CLI
     *       normalizes U+000A to logical Ctrl+J bound to {@code insert_newline}
     *       (openai/codex #20555, PR #20798); {@code EventParser} surfaces LF
     *       as a plain {@code '\n'} character.</li>
     *   <li>Shift+Enter / Alt+Enter when a terminal reports the modifier —
     *       Codex's editor default {@code insert_newline} includes both.</li>
     * </ul>
     */
    public static boolean isNewlineEnter(KeyEvent event) {
        if (event.code() == KeyCode.CHAR && "\n".equals(event.string())) {
            return true;
        }
        if (!event.isKey(KeyCode.ENTER)) {
            return false;
        }
        return event.hasShift() || event.hasAlt();
    }
}
