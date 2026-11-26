package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * Application-level keybindings, aligned with pi's {@code app.*} namespace
 * (Phase 3 design §7.2).
 *
 * <p>The coding-agent module defines the key IDs and default strokes using a
 * neutral {@link KeyStroke} (no TamboUI dependency); the TUI module maps its
 * {@code KeyEvent} to {@link KeyStroke} via its adapter and asks this class
 * which action fired. L7 W8（K17）起 {@link #create()} 读
 * {@code <agentDir>/keybindings.json} 用户覆盖（legacy 名迁移、空数组解绑；
 * 仅覆盖本类两表内的动作）。</p>
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
    // 批 34（S8；pi keybindings.ts app.thinking.save）：thinking 选择器内 ctrl+s。
    public static final String THINKING_SAVE = "app.thinking.save";      // Ctrl+S（仅选择器）
    public static final String EXTERNAL_EDITOR = "app.editor.external";  // Ctrl+G
    public static final String FOLLOW_UP = "app.message.followUp";       // Alt+Enter（Windows/WSL ⇒ Ctrl+Q）
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
    // L7 W7（K14；pi keybindings.ts:75-78 app.clipboard.pasteImage）：ctrl+v / alt+v。
    public static final String PASTE_IMAGE = "app.clipboard.pasteImage";

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

    // ── app.models.*（scoped-models 选择器；pi keybindings.ts:119-141）─
    public static final String MODELS_SAVE = "app.models.save";
    public static final String MODELS_ENABLE_ALL = "app.models.enableAll";
    public static final String MODELS_CLEAR_ALL = "app.models.clearAll";
    public static final String MODELS_TOGGLE_PROVIDER = "app.models.toggleProvider";
    public static final String MODELS_REORDER_UP = "app.models.reorderUp";
    public static final String MODELS_REORDER_DOWN = "app.models.reorderDown";

    // ── tui.select.*／tui.input.newLine（批 17 T4，docs/28 §1.8；pi tui/
    // keybindings.ts:143-158 默认键逐字）——overlay 表（批 9 C1 教训：同表污染主屏）。
    public static final String SELECT_UP = "tui.select.up";           // up
    public static final String SELECT_DOWN = "tui.select.down";       // down
    public static final String SELECT_CONFIRM = "tui.select.confirm"; // enter
    public static final String SELECT_CANCEL = "tui.select.cancel";   // escape, ctrl+c
    // docs/22：pi tui keybindings.ts 的 tui.select.pageUp/pageDown 默认键。
    public static final String SELECT_PAGE_UP = "tui.select.pageUp";     // pageup
    public static final String SELECT_PAGE_DOWN = "tui.select.pageDown"; // pagedown
    public static final String INPUT_NEW_LINE = "tui.input.newLine";  // shift+enter, ctrl+j

    // ── tui.altScreen.*（L7 W6 S2a/K21；pi tui/keybindings.ts 默认键）────
    public static final String ALT_SEARCH = "tui.altScreen.search";              // ctrl+shift+f
    public static final String ALT_SEARCH_NEXT = "tui.altScreen.searchNext";     // enter, ctrl+g
    public static final String ALT_SEARCH_PREVIOUS = "tui.altScreen.searchPrevious"; // shift+enter, ctrl+shift+g
    public static final String ALT_SEARCH_CLOSE = "tui.altScreen.searchClose";   // escape

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
    private final @Nullable Path configPath;

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
        this(windowsKeybindings, null);
    }

    private KeybindingsManager(boolean windowsKeybindings, @Nullable Path configPath) {
        this.configPath = configPath;
        installDefaults(windowsKeybindings);
        if (configPath != null) {
            applyUserBindings(loadUserBindings(configPath));
        }
    }

    /**
     * pi {@code KeybindingsManager.create}（keybindings.ts:392-396）：从
     * {@code <agentDir>/keybindings.json} 读用户覆盖；文件缺失/损坏 ⇒ 零覆盖。
     *
     * @return the manager with user overrides applied
     */
    public static KeybindingsManager create() {
        return create(FileSettingsStorage.defaultAgentDir());
    }

    /** 可注入 agent 目录的构造（测试缝）。 */
    static KeybindingsManager create(Path agentDir) {
        return new KeybindingsManager(
            useWindowsKeybindings(System.getProperty("os.name", ""), System.getenv()),
            agentDir.resolve("keybindings.json"));
    }

    /** pi {@code reload}：重建默认表后重新应用文件覆盖。 */
    public void reload() {
        if (configPath == null) {
            return;
        }
        bindings.clear();
        overlayBindings.clear();
        installDefaults(useWindowsKeybindings(System.getProperty("os.name", ""), System.getenv()));
        applyUserBindings(loadUserBindings(configPath));
    }

    /** pi {@code getEffectiveConfig}：全局＋overlay 两表的只读合并视图。 */
    public Map<String, List<KeyStroke>> effectiveBindings() {
        var merged = new LinkedHashMap<String, List<KeyStroke>>(bindings);
        merged.putAll(overlayBindings);
        return Map.copyOf(merged);
    }

    /**
     * 读并迁移用户在 {@code keybindings.json} 里的覆盖。
     *
     * <p>pi {@code loadFromFile}／{@code migrateKeybindingsConfig}／
     * {@code toKeybindingsConfig} 对位：跳过 {@code $schema}，值必须是
     * 字符串或字符串数组；legacy 名迁移；新旧同存以新名为准。</p>
     */
    private static Map<String, List<KeyStroke>> loadUserBindings(Path path) {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        JsonNode root;
        try {
            var text = Files.readString(path);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            root = Json.mapper().readTree(text);
        } catch (Exception e) {
            return Map.of();
        }
        if (root == null || !root.isObject()) {
            return Map.of();
        }
        var raw = new LinkedHashMap<String, JsonNode>();
        for (var it = root.fields(); it.hasNext();) {
            var entry = it.next();
            if ("$schema".equals(entry.getKey())) {
                continue;
            }
            var value = entry.getValue();
            if (value.isTextual() || value.isArray()) {
                raw.put(entry.getKey(), value);
            }
        }
        var migrated = new LinkedHashMap<String, JsonNode>();
        for (var entry : raw.entrySet()) {
            var target = KeybindingsNames.LEGACY_NAMES.getOrDefault(
                entry.getKey(), entry.getKey());
            if (!target.equals(entry.getKey()) && raw.containsKey(target)) {
                continue;
            }
            migrated.put(target, entry.getValue());
        }
        var result = new HashMap<String, List<KeyStroke>>();
        for (var entry : migrated.entrySet()) {
            var strokes = parseValue(entry.getValue());
            if (strokes != null) {
                result.put(entry.getKey(), strokes);
            }
        }
        return result;
    }

    /** 值解析：字符串 ⇒ 单击；数组 ⇒ 多键（任一非法整项丢弃）；空数组 ⇒ 解绑。 */
    private static @Nullable List<KeyStroke> parseValue(JsonNode value) {
        if (value.isTextual()) {
            var stroke = parseStroke(value.asText());
            return stroke == null ? null : List.of(stroke);
        }
        var strokes = new java.util.ArrayList<KeyStroke>();
        for (var element : value) {
            if (!element.isTextual()) {
                return null;
            }
            var stroke = parseStroke(element.asText());
            if (stroke == null) {
                return null;
            }
            strokes.add(stroke);
        }
        return List.copyOf(strokes);
    }

    /**
     * pi {@code KeyId} 串 ⇒ 中立 {@link KeyStroke}（{@code ctrl+alt+shift+key}）。
     * {@code escape} 归一为 {@code esc}；未知修饰/键名 ⇒ null。
     */
    static @Nullable KeyStroke parseStroke(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var parts = text.trim().toLowerCase(Locale.ROOT).split("\\+");
        if (parts.length == 0 || parts[parts.length - 1].isEmpty()) {
            return null;
        }
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i]) {
                case "ctrl" -> ctrl = true;
                case "alt" -> alt = true;
                case "shift" -> shift = true;
                default -> {
                    return null;
                }
            }
        }
        var key = parts[parts.length - 1];
        if ("escape".equals(key)) {
            key = "esc";
        } else if ("space".equals(key)) {
            key = " ";
        }
        if (key.length() != 1 && !KeybindingsNames.NAMED_KEYS.contains(key)) {
            return null;
        }
        return new KeyStroke(key, ctrl, alt, shift);
    }

    /** 覆盖落位：动作在哪个表就替换哪个表；两表同有（copy）两表都换；未知动作忽略。 */
    private void applyUserBindings(Map<String, List<KeyStroke>> userBindings) {
        for (var entry : userBindings.entrySet()) {
            if (bindings.containsKey(entry.getKey())) {
                bindings.put(entry.getKey(), entry.getValue());
            }
            if (overlayBindings.containsKey(entry.getKey())) {
                overlayBindings.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 默认两表（pi keybindings.ts 默认键逐字）。 */
    private void installDefaults(boolean windowsKeybindings) {
        bindings.put(INTERRUPT, List.of(KeyStroke.of("esc", false, false, false)));
        bindings.put(CLEAR, List.of(KeyStroke.of("c", true, false, false)));
        bindings.put(EXIT, List.of(KeyStroke.of("d", true, false, false)));
        bindings.put(MODEL_CYCLE, List.of(KeyStroke.of("p", true, false, false)));
        bindings.put(MODEL_SELECT, List.of(KeyStroke.of("l", true, false, false)));
        bindings.put(THINKING_CYCLE, List.of(KeyStroke.of("tab", false, false, true)));
        bindings.put(TOOLS_EXPAND, List.of(KeyStroke.of("o", true, false, false)));
        bindings.put(THINKING_TOGGLE, List.of(KeyStroke.of("t", true, false, false)));
        bindings.put(EXTERNAL_EDITOR, List.of(KeyStroke.of("g", true, false, false)));
        bindings.put(FOLLOW_UP, windowsKeybindings
            ? List.of(KeyStroke.of("q", true, false, false))
            : List.of(KeyStroke.of("enter", false, true, false)));
        // L7 W7（K13/K14；pi keybindings.ts:63,75-78）：全局复制/粘贴图片。
        // MESSAGE_COPY 另在 overlay 表（tree 选择器语境）同键，两表各解各的。
        bindings.put(MESSAGE_COPY, List.of(KeyStroke.of("x", true, false, false)));
        bindings.put(PASTE_IMAGE, List.of(
            KeyStroke.of("v", true, false, false),
            KeyStroke.of("v", false, true, false)));
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
        // scoped-models 六键只在选择器语境生效，不进全局 resolve。
        overlayBindings.put(MODELS_SAVE, List.of(KeyStroke.of("s", true, false, false)));
        // 批 34（S8）：thinking 选择器「存为默认」——overlay 表，主屏 resolve 不命中。
        overlayBindings.put(THINKING_SAVE, List.of(KeyStroke.of("s", true, false, false)));
        overlayBindings.put(MODELS_ENABLE_ALL, List.of(KeyStroke.of("a", true, false, false)));
        overlayBindings.put(MODELS_CLEAR_ALL, List.of(KeyStroke.of("x", true, false, false)));
        overlayBindings.put(MODELS_TOGGLE_PROVIDER, List.of(KeyStroke.of("p", true, false, false)));
        overlayBindings.put(MODELS_REORDER_UP, List.of(KeyStroke.of("up", false, true, false)));
        overlayBindings.put(MODELS_REORDER_DOWN, List.of(KeyStroke.of("down", false, true, false)));
        // 批 17（T4）：pi tui/keybindings.ts:143-158 默认键逐字——扩展对话框五键。
        overlayBindings.put(SELECT_UP, List.of(KeyStroke.of("up", false, false, false)));
        overlayBindings.put(SELECT_DOWN, List.of(KeyStroke.of("down", false, false, false)));
        overlayBindings.put(SELECT_PAGE_UP, List.of(KeyStroke.of("pageup", false, false, false)));
        overlayBindings.put(SELECT_PAGE_DOWN, List.of(KeyStroke.of("pagedown", false, false, false)));
        overlayBindings.put(SELECT_CONFIRM, List.of(KeyStroke.of("enter", false, false, false)));
        overlayBindings.put(SELECT_CANCEL, List.of(
            KeyStroke.of("esc", false, false, false),
            KeyStroke.of("c", true, false, false)));
        overlayBindings.put(INPUT_NEW_LINE, List.of(
            KeyStroke.of("enter", false, false, true),
            KeyStroke.of("j", true, false, false)));
        // L7 W6（S2a/K21）：转录搜索四动作（overlay 语境；主屏 resolve 不命中）。
        overlayBindings.put(ALT_SEARCH, List.of(KeyStroke.of("f", true, false, true)));
        overlayBindings.put(ALT_SEARCH_NEXT, List.of(
            KeyStroke.of("enter", false, false, false),
            KeyStroke.of("g", true, false, false)));
        overlayBindings.put(ALT_SEARCH_PREVIOUS, List.of(
            KeyStroke.of("enter", false, false, true),
            KeyStroke.of("g", true, false, true)));
        overlayBindings.put(ALT_SEARCH_CLOSE, List.of(KeyStroke.of("esc", false, false, false)));
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
