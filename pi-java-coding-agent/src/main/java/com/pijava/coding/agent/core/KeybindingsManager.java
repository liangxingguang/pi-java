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
    public static final String DEQUEUE = "app.message.dequeue";          // Alt+Up（Windows/WSL ⇒ Alt+Q）

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

    // ── app.session.*（批 14，docs/25 T3；pi core/keybindings.ts 默认键）─
    // 选择器六键（overlay 表——ctrl+p/ctrl+d 与全局 MODEL_CYCLE/EXIT 撞名，批 9 C1 同构）
    public static final String SESSION_TOGGLE_NAMED_FILTER = "app.session.toggleNamedFilter"; // ctrl+n
    public static final String SESSION_TOGGLE_PATH = "app.session.togglePath";                // ctrl+p
    public static final String SESSION_TOGGLE_SORT = "app.session.toggleSort";                // ctrl+s
    public static final String SESSION_RENAME = "app.session.rename";                         // ctrl+r
    public static final String SESSION_DELETE = "app.session.delete";                         // ctrl+d
    public static final String SESSION_DELETE_NONINVASIVE = "app.session.deleteNoninvasive";  // ctrl+backspace
    // pi `defaultKeys: []`（keybindings.ts:79-82）声明无绑定——全局表空表注册
    public static final String SESSION_NEW = "app.session.new";
    public static final String SESSION_TREE = "app.session.tree";
    public static final String SESSION_FORK = "app.session.fork";
    public static final String SESSION_RESUME = "app.session.resume";

    // ── tui.select.*／tui.input.newLine（批 17 T4，docs/28 §1.8；pi tui/
    // keybindings.ts:143-158 默认键逐字）——overlay 表（批 9 C1 教训：同表污染主屏）。
    public static final String SELECT_UP = "tui.select.up";           // up
    public static final String SELECT_DOWN = "tui.select.down";       // down
    public static final String SELECT_CONFIRM = "tui.select.confirm"; // enter
    public static final String SELECT_CANCEL = "tui.select.cancel";   // escape, ctrl+c
    public static final String INPUT_NEW_LINE = "tui.input.newLine";  // shift+enter, ctrl+j

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
     * 全局动作表（11 实键＋4 空绑定 app.session.new/tree/fork/resume）与
     * overlay 语境动作表（12 个 app.tree.*／app.message.copy＋6 个选择器
     * app.session.*）**分持**——批 9 评审 C1：同表时 {@link #resolve} 的
     * hash 序会把 ctrl+l/u/a 等解析成 tree 动作，主屏按键被静默吞掉。
     * pi 无此歧义（全局 handler 按动作逐个查，即本类的 {@link #matches} 形）。
     */
    private final Map<String, List<KeyStroke>> bindings = new HashMap<>();
    private final Map<String, List<KeyStroke>> overlayBindings = new HashMap<>();

    /** Create a manager pre-populated with the default keybindings. */
    public KeybindingsManager() {
        this(useWindowsKeybindings(System.getProperty("os.name", ""), System.getenv()));
    }

    /**
     * 平台旗可注入的构造（测试面；生产走 {@link #KeybindingsManager()} 的系统探测）。
     *
     * @param windowsKeybindings pi 的 {@code windowsKeybindings} 模块级常量对位
     */
    KeybindingsManager(boolean windowsKeybindings) {
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
        // pi keybindings.ts:71-74（裁决③）：Windows/WSL ⇒ alt+q，其余 alt+up——
        // 单键不双绑（双绑会让 Alt+Q 在非 Windows 吃掉编辑器输入）。
        bindings.put(DEQUEUE, windowsKeybindings
            ? List.of(KeyStroke.of("q", false, true, false))
            : List.of(KeyStroke.of("up", false, true, false)));
        // 批 14（docs/25 T3）：pi `defaultKeys: []` 空绑定——注册动作但不挂键
        // （resolve 永不命中、actionIds 可见、strokeFor ⇒ null ≙ pi formatKeys([]) ⇒ ""）。
        bindings.put(SESSION_NEW, List.of());
        bindings.put(SESSION_TREE, List.of());
        bindings.put(SESSION_FORK, List.of());
        bindings.put(SESSION_RESUME, List.of());

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
        // 批 14（docs/25 T3）：会话选择器六键——overlay 表（ctrl+p/ctrl+d 与全局
        // MODEL_CYCLE/EXIT 撞名，选择器内按动作 matches 解析，全局零影响）。
        // ctrl+backspace 在不上报该组合的终端是死键（pi 注释同病，窄差 D76）。
        overlayBindings.put(SESSION_TOGGLE_NAMED_FILTER, List.of(KeyStroke.of("n", true, false, false)));
        overlayBindings.put(SESSION_TOGGLE_PATH, List.of(KeyStroke.of("p", true, false, false)));
        overlayBindings.put(SESSION_TOGGLE_SORT, List.of(KeyStroke.of("s", true, false, false)));
        overlayBindings.put(SESSION_RENAME, List.of(KeyStroke.of("r", true, false, false)));
        overlayBindings.put(SESSION_DELETE, List.of(KeyStroke.of("d", true, false, false)));
        overlayBindings.put(SESSION_DELETE_NONINVASIVE, List.of(KeyStroke.of("backspace", true, false, false)));
        // 批 17（T4）：pi tui/keybindings.ts:143-158 默认键逐字——扩展对话框五键。
        overlayBindings.put(SELECT_UP, List.of(KeyStroke.of("up", false, false, false)));
        overlayBindings.put(SELECT_DOWN, List.of(KeyStroke.of("down", false, false, false)));
        overlayBindings.put(SELECT_CONFIRM, List.of(KeyStroke.of("enter", false, false, false)));
        overlayBindings.put(SELECT_CANCEL, List.of(
            KeyStroke.of("esc", false, false, false),
            KeyStroke.of("c", true, false, false)));
        overlayBindings.put(INPUT_NEW_LINE, List.of(
            KeyStroke.of("enter", false, false, true),
            KeyStroke.of("j", true, false, false)));
    }

    /**
     * pi {@code useWindowsKeybindings}（keybindings.ts:16-20 逐字，docs/21 §3.8）：
     * win32，或 linux＋WSL 环境变量（{@code WSL_DISTRO_NAME}／{@code WSL_INTEROP}）。
     * JVM 的 {@code os.name} 在 Windows 含 {@code "windows"}、Linux 含 {@code "linux"}，
     * 判定串与 pi 的平台集合对齐（裁决③）。
     */
    static boolean useWindowsKeybindings(String osName, java.util.Map<String, String> env) {
        var name = osName.toLowerCase(java.util.Locale.ROOT);
        return name.contains("windows")
            || (name.contains("linux")
                && (env.containsKey("WSL_DISTRO_NAME") || env.containsKey("WSL_INTEROP")));
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
