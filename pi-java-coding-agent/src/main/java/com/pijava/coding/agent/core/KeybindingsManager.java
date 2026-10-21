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
 * which action fired. User overrides from {@code keybindings.json} → Phase 6
 * （既有约束，tree 键同源——窄差 D45）。</p>
 *
 * <p>批 9（docs/19 §3.11）：一动作可配**多键**（pi 的 foldOrUp/unfoldOrDown
 * 各有 ctrl／alt 两键）；新增 12 个 app.tree.*／app.message.copy 动作。
 * 全局分派用 {@link #resolve}（任一命中即返）；overlay 内用 pi 同形的
 * {@link #matches} 按动作逐个判定——同名键（ctrl+d/t/o/l 与全局动作撞车）
 * 在 overlay 上下文按 tree 语义解析，互不染指。</p>
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

    // ── app.tree.*／app.message.copy（docs/19 §3.11，pi keybindings.ts 默认键）─
    public static final String TREE_FOLD_OR_UP = "app.tree.foldOrUp";     // ctrl+left, alt+left
    public static final String TREE_UNFOLD_OR_DOWN = "app.tree.unfoldOrDown"; // ctrl+right, alt+right
    public static final String TREE_EDIT_LABEL = "app.tree.editLabel";    // shift+l
    public static final String TREE_TOGGLE_LABEL_TIMESTAMP = "app.tree.toggleLabelTimestamp"; // shift+t
    public static final String TREE_FILTER_DEFAULT = "app.tree.filter.default";       // ctrl+d
    public static final String TREE_FILTER_NO_TOOLS = "app.tree.filter.noTools";      // ctrl+t
    public static final String TREE_FILTER_USER_ONLY = "app.tree.filter.userOnly";    // ctrl+u
    public static final String TREE_FILTER_LABELED = "app.tree.filter.labeledOnly";   // ctrl+l
    public static final String TREE_FILTER_ALL = "app.tree.filter.all";               // ctrl+a
    public static final String TREE_FILTER_CYCLE_FORWARD = "app.tree.filter.cycleForward";   // ctrl+o
    public static final String TREE_FILTER_CYCLE_BACKWARD = "app.tree.filter.cycleBackward"; // shift+ctrl+o
    public static final String MESSAGE_COPY = "app.message.copy";         // ctrl+x

    /**
     * Neutral key representation produced by the TUI adapter.
     *
     * @param key   lowercase key name ("esc", "enter", "up", "c", …)
     * @param ctrl  control modifier
     * @param alt   alt modifier
     * @param shift shift modifier
     */
    public record KeyStroke(String key, boolean ctrl, boolean alt, boolean shift) {
        /**
         * Convenience factory for a neutral key stroke.
         *
         * @param key   lowercase key name ("esc", "enter", "up", "c", …)
         * @param ctrl  control modifier
         * @param alt   alt modifier
         * @param shift shift modifier
         * @return the new {@link KeyStroke}
         */
        public static KeyStroke of(String key, boolean ctrl, boolean alt, boolean shift) {
            return new KeyStroke(key, ctrl, alt, shift);
        }
    }

    /**
     * 全局动作表（11 个）与 overlay 语境动作表（12 个 app.tree.*／
     * app.message.copy）**分持**——批 9 评审 C1：同表时 {@link #resolve} 的
     * hash 序会把 ctrl+l/u/a 等解析成 tree 动作，主屏按键被静默吞掉。
     * pi 无此歧义（全局 handler 按动作逐个查，即本类的 {@link #matches} 形）。
     */
    private final Map<String, List<KeyStroke>> bindings = new HashMap<>();
    private final Map<String, List<KeyStroke>> overlayBindings = new HashMap<>();

    /** Create a manager pre-populated with the default keybindings. */
    public KeybindingsManager() {
        bindings.put(INTERRUPT, List.of(KeyStroke.of("esc", false, false, false)));
        bindings.put(CLEAR, List.of(KeyStroke.of("c", true, false, false)));
        bindings.put(EXIT, List.of(KeyStroke.of("d", true, false, false)));
        bindings.put(MODEL_CYCLE, List.of(KeyStroke.of("p", true, false, false)));
        bindings.put(MODEL_SELECT, List.of(KeyStroke.of("l", true, false, false)));
        bindings.put(THINKING_CYCLE, List.of(KeyStroke.of("tab", false, false, true)));
        bindings.put(TOOLS_EXPAND, List.of(KeyStroke.of("o", true, false, false)));
        bindings.put(THINKING_TOGGLE, List.of(KeyStroke.of("t", true, false, false)));
        bindings.put(EXTERNAL_EDITOR, List.of(KeyStroke.of("g", true, false, false)));
        bindings.put(FOLLOW_UP, List.of(KeyStroke.of("enter", false, true, false)));
        bindings.put(DEQUEUE, List.of(KeyStroke.of("up", false, true, false)));

        overlayBindings.put(TREE_FOLD_OR_UP, List.of(
            KeyStroke.of("left", true, false, false),
            KeyStroke.of("left", false, true, false)));
        overlayBindings.put(TREE_UNFOLD_OR_DOWN, List.of(
            KeyStroke.of("right", true, false, false),
            KeyStroke.of("right", false, true, false)));
        overlayBindings.put(TREE_EDIT_LABEL, List.of(KeyStroke.of("l", false, false, true)));
        overlayBindings.put(TREE_TOGGLE_LABEL_TIMESTAMP,
            List.of(KeyStroke.of("t", false, false, true)));
        overlayBindings.put(TREE_FILTER_DEFAULT, List.of(KeyStroke.of("d", true, false, false)));
        overlayBindings.put(TREE_FILTER_NO_TOOLS, List.of(KeyStroke.of("t", true, false, false)));
        overlayBindings.put(TREE_FILTER_USER_ONLY, List.of(KeyStroke.of("u", true, false, false)));
        overlayBindings.put(TREE_FILTER_LABELED, List.of(KeyStroke.of("l", true, false, false)));
        overlayBindings.put(TREE_FILTER_ALL, List.of(KeyStroke.of("a", true, false, false)));
        overlayBindings.put(TREE_FILTER_CYCLE_FORWARD,
            List.of(KeyStroke.of("o", true, false, false)));
        overlayBindings.put(TREE_FILTER_CYCLE_BACKWARD,
            List.of(KeyStroke.of("o", true, false, true)));
        overlayBindings.put(MESSAGE_COPY, List.of(KeyStroke.of("x", true, false, false)));
    }

    /**
     * Resolve a stroke to a **global** action ID（overlay 语境动作不参与——
     * 它们只经 {@link #matches} 按动作查询）。
     *
     * @param stroke the neutral stroke
     * @return the action ID or {@code null} when unbound
     */
    public String resolve(KeyStroke stroke) {
        for (var entry : bindings.entrySet()) {
            if (entry.getValue().contains(stroke)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * pi {@code kb.matches(keyData, action)} 对位：stroke 是否命中**指定**动作
     * 的任一键（两表都查）。overlay 内按动作逐个判定（pi handleInput 的
     * 分派形），同名键在不同上下文各解各的。
     */
    public boolean matches(String actionId, KeyStroke stroke) {
        var strokes = overlayBindings.get(actionId);
        if (strokes == null) {
            strokes = bindings.get(actionId);
        }
        return strokes != null && strokes.contains(stroke);
    }

    /** All bound action IDs（两表并集）, for the {@code /hotkeys} command. */
    public List<String> actionIds() {
        var ids = new java.util.ArrayList<>(bindings.keySet());
        ids.addAll(overlayBindings.keySet());
        return List.copyOf(ids);
    }

    /** The default (first) stroke for an action ID (or null). */
    public KeyStroke strokeFor(String actionId) {
        var strokes = bindings.get(actionId);
        if (strokes == null) {
            strokes = overlayBindings.get(actionId);
        }
        return strokes == null || strokes.isEmpty() ? null : strokes.get(0);
    }
}
