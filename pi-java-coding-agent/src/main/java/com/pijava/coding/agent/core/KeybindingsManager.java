package com.pijava.coding.agent.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-level keybindings, aligned with pi's {@code app.*} namespace
 * (Phase 3 design §7.2).
 *
 * <p>The coding-agent module defines the key IDs and default strokes using a
 * neutral {@link KeyStroke} (no TamboUI dependency); the TUI module maps its
 * {@code KeyEvent} to {@link KeyStroke} via its adapter and asks this class
 * which action fired. User overrides from {@code keybindings.json} → Phase 6.</p>
 */
public final class KeybindingsManager {

    // ── app.* action IDs (Phase 3 core subset) ────────────────
    public static final String INTERRUPT = "app.interrupt";              // Esc
    public static final String CLEAR = "app.clear";                      // Ctrl+C
    public static final String EXIT = "app.exit";                        // Ctrl+D
    public static final String MODEL_CYCLE = "app.model.cycleForward";   // Ctrl+P
    public static final String MODEL_SELECT = "app.model.select";        // Ctrl+L
    public static final String THINKING_CYCLE = "app.thinking.cycle";    // Shift+Tab
    public static final String TOOLS_EXPAND = "app.tools.expand";        // Ctrl+O
    public static final String THINKING_TOGGLE = "app.thinking.toggle";  // Ctrl+T
    public static final String EXTERNAL_EDITOR = "app.editor.external";  // Ctrl+G
    public static final String FOLLOW_UP = "app.message.followUp";       // Alt+Enter
    public static final String DEQUEUE = "app.message.dequeue";          // Alt+Up

    /**
     * Neutral key representation produced by the TUI adapter.
     *
     * @param key   lowercase key name ("esc", "enter", "up", "c", …)
     * @param ctrl  control modifier
     * @param alt   alt modifier
     * @param shift shift modifier
     */
    public record KeyStroke(String key, boolean ctrl, boolean alt, boolean shift) {
        public static KeyStroke of(String key, boolean ctrl, boolean alt, boolean shift) {
            return new KeyStroke(key, ctrl, alt, shift);
        }
    }

    private final Map<String, KeyStroke> bindings = new HashMap<>();

    public KeybindingsManager() {
        bindings.put(INTERRUPT, KeyStroke.of("esc", false, false, false));
        bindings.put(CLEAR, KeyStroke.of("c", true, false, false));
        bindings.put(EXIT, KeyStroke.of("d", true, false, false));
        bindings.put(MODEL_CYCLE, KeyStroke.of("p", true, false, false));
        bindings.put(MODEL_SELECT, KeyStroke.of("l", true, false, false));
        bindings.put(THINKING_CYCLE, KeyStroke.of("tab", false, false, true));
        bindings.put(TOOLS_EXPAND, KeyStroke.of("o", true, false, false));
        bindings.put(THINKING_TOGGLE, KeyStroke.of("t", true, false, false));
        bindings.put(EXTERNAL_EDITOR, KeyStroke.of("g", true, false, false));
        bindings.put(FOLLOW_UP, KeyStroke.of("enter", false, true, false));
        bindings.put(DEQUEUE, KeyStroke.of("up", false, true, false));
    }

    /**
     * Resolve a stroke to an action ID.
     *
     * @param stroke the neutral stroke
     * @return the action ID or {@code null} when unbound
     */
    public String resolve(KeyStroke stroke) {
        for (var entry : bindings.entrySet()) {
            if (entry.getValue().equals(stroke)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** All bound action IDs, for the {@code /hotkeys} command. */
    public List<String> actionIds() {
        return List.copyOf(bindings.keySet());
    }

    /** The default stroke for an action ID (or null). */
    public KeyStroke strokeFor(String actionId) {
        return bindings.get(actionId);
    }
}
