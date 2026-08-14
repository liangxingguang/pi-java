package com.pijava.tui.util;

import java.util.Collection;

import com.pijava.coding.agent.core.KeybindingsManager;

import dev.tamboui.layout.Constraint;
import dev.tamboui.style.Color;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.elements.Column;
import dev.tamboui.toolkit.elements.MarkupTextElement;
import dev.tamboui.toolkit.elements.Panel;
import dev.tamboui.toolkit.elements.Row;
import dev.tamboui.toolkit.elements.Spacer;
import dev.tamboui.toolkit.elements.TextElement;
import dev.tamboui.toolkit.elements.TextAreaElement;
import dev.tamboui.tui.PostRenderProcessor;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextAreaState;

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

    /** Create a full-screen TUI runner (Panama backend via ServiceLoader). */
    public static ToolkitRunner createRunner(PostRenderProcessor extraProcessor) throws Exception {
        return ToolkitRunner.create(TuiConfig.builder()
            .alternateScreen(true)
            .hideCursor(true)
            .bracketedPaste(true)
            .postRenderProcessor(extraProcessor)
            .build());
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

    /** Whether the event is a plain Enter (submit). */
    public static boolean isPlainEnter(KeyEvent event) {
        if (event.hasAlt() || event.hasShift()) {
            return false;
        }
        if (event.isKey(KeyCode.ENTER)) {
            return true;
        }
        // Some terminals/backends report Enter as CHAR('\r') / CHAR('\n')
        // instead of KeyCode.ENTER (e.g. via ConPTY). Treat those as Enter.
        return event.code() == KeyCode.CHAR
            && ("\r".equals(event.string()) || "\n".equals(event.string()));
    }

    /** Whether the event is Shift+Enter (newline in the editor). */
    public static boolean isShiftEnter(KeyEvent event) {
        return event.isKey(KeyCode.ENTER) && event.hasShift() && !event.hasAlt();
    }
}
