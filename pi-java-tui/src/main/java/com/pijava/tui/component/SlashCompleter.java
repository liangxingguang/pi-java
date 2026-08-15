package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Slash-command completion popup (Codex-CLI style). While the editor text
 * starts with {@code "/"} the panel lists matching commands; Up/Down moves
 * the highlight, Tab completes the highlighted command, Esc closes the panel.
 * The rendered element sits directly above the editor.
 */
public final class SlashCompleter {

    /** One slash command with its argument hint and description. */
    public record CommandItem(String name, String hint, String description) {
        public String display() {
            String line = "/" + name;
            if (hint != null && !hint.isEmpty()) {
                line += " " + hint;
            }
            if (description != null && !description.isEmpty()) {
                line += "  — " + description;
            }
            return line.length() <= 64 ? line : line.substring(0, 64);
        }
    }

    /** Result of feeding a key to the completer. */
    public enum KeyAction { IGNORED, HANDLED, COMPLETE }

    private static final int MAX_ROWS = 8;

    private final List<CommandItem> allItems;
    private final List<CommandItem> matches = new ArrayList<>();
    private int selected;
    private boolean active;

    public SlashCompleter(List<CommandItem> items) {
        this.allItems = List.copyOf(items);
    }

    /** Refresh from the current editor text; closes unless it is "/..." input. */
    public void update(String text) {
        if (text == null || !text.startsWith("/")
                || text.indexOf('\n') >= 0 || text.indexOf(' ') >= 0) {
            active = false;
            matches.clear();
            return;
        }
        String query = text.substring(1);
        matches.clear();
        boolean exact = false;
        for (var item : allItems) {
            if (item.name().equals(query)) {
                exact = true;
            }
            if (item.name().startsWith(query)) {
                matches.add(item);
            }
        }
        // A full command name closes the panel (Codex behavior).
        active = !exact && !matches.isEmpty();
        selected = 0;
    }

    /** Whether the popup is currently visible. */
    public boolean active() {
        return active;
    }

    /** The currently matching commands (filtered, in registry order). */
    public List<CommandItem> matches() {
        return List.copyOf(matches);
    }

    /** The full {@code "/name"} of the highlighted command, or null. */
    public String selectedName() {
        return matches.isEmpty() ? null : "/" + matches.get(selected).name();
    }

    /** Handle navigation keys while active. */
    public KeyAction onKeyEvent(KeyEvent event) {
        if (!active) {
            return KeyAction.IGNORED;
        }
        if (event.isUp()) {
            selected = Math.max(0, selected - 1);
            return KeyAction.HANDLED;
        }
        if (event.isDown()) {
            selected = Math.min(matches.size() - 1, selected + 1);
            return KeyAction.HANDLED;
        }
        boolean tab = event.isKey(KeyCode.TAB) || event.isChar('\t');
        if (tab && !event.hasShift()) {
            return KeyAction.COMPLETE;
        }
        if (event.isCancel()) {
            active = false;
            matches.clear();
            return KeyAction.HANDLED;
        }
        return KeyAction.IGNORED;
    }

    /** The popup element, or null when inactive. */
    public Element render() {
        if (!active) {
            return null;
        }
        var rows = new ArrayList<Element>();
        int shown = Math.min(matches.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            String line = matches.get(i).display();
            rows.add(i == selected
                ? TamboUIAdapter.markupText("[cyan]▸ " + line + "[/]")
                : TamboUIAdapter.markupText("  " + line));
        }
        if (matches.size() > shown) {
            rows.add(TamboUIAdapter.markupText(
                "[dim]  … " + (matches.size() - shown) + " more[/]"));
        }
        return TamboUIAdapter.column(rows);
    }

    /** Number of popup rows (drives the inline bottom-region height). */
    public int lineCount() {
        if (!active) {
            return 0;
        }
        int shown = Math.min(matches.size(), MAX_ROWS);
        return matches.size() > shown ? shown + 1 : shown;
    }
}
