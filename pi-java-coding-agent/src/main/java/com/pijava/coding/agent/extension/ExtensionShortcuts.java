package com.pijava.coding.agent.extension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pijava.coding.agent.core.KeyIdParser;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;

/**
 * 批 26（docs/37 T9，E5-6）：pi {@code getShortcuts}（runner.ts:673-716 逐字）——
 * 扩展快捷键的冲突解决。
 *
 * <p>三规则（次序即 pi）：</p>
 * <ol>
 *   <li>撞<b>保留</b>内置键 ⇒ 跳过＋诊断「conflicts with built-in shortcut. Skipping.」；</li>
 *   <li>撞非保留内置键 ⇒ 扩展赢＋诊断「...is built-in shortcut for X and Y. Using Y.」；</li>
 *   <li>扩展互撞 ⇒ 后者赢＋诊断「...registered by both A and B. Using B.」。</li>
 * </ol>
 *
 * <p>保留表对位 pi 的 {@code RESERVED_KEYBINDINGS_FOR_EXTENSION_CONFLICTS}
 * （runner.ts:96-115，18 条）：java 已知的 app.* 动作经 {@link KeybindingsManager}
 * 解析；java 无对应动作的 7 条（app.suspend/app.model.cycleBackward/tui.* 四条）
 * 以硬编码等价键补齐（tui.input.submit=enter、tui.select.confirm=enter、
 * tui.select.cancel=esc、tui.input.copy=ctrl+c、tui.editor.deleteToLineEnd=ctrl+u、
 * app.suspend=ctrl+z〔非 Windows〕、app.model.cycleBackward=shift+ctrl+p〔非
 * Windows〕/alt+p〔Windows〕）。</p>
 */
public final class ExtensionShortcuts {

    /** 解决产出：一条可派发的扩展快捷键。 */
    public record ResolvedShortcut(KeyStroke stroke, String description, String extensionPath,
                                   ExtensionShortcut.ShortcutHandler handler) {}

    /** 解决结果：可派发表＋诊断（pi shortcutDiagnostics 对位，warning 型）。 */
    public record Resolution(List<ResolvedShortcut> shortcuts, List<String> diagnostics) {}

    private ExtensionShortcuts() {}

    /**
     * pi {@code getShortcuts(resolvedKeybindings)}：按扩展加载序拍平冲突。
     *
     * @param keys        内置键位表（保留/非保留内置键的数据源）
     * @param windows     Windows/WSL 键位族（app.model.cycleBackward/app.suspend 的平台臂）
     * @param byExtension 扩展加载序的快捷键表（extensionPath → 〔keyId → 注册〕）
     */
    public static Resolution resolve(KeybindingsManager keys, boolean windows,
                                     LinkedHashMap<String, LinkedHashMap<String, ExtensionShortcut>>
                                         byExtension) {
        var diagnostics = new ArrayList<String>();
        // pi buildBuiltinKeybindings（runner.ts:119-136）：stroke → (动作, 保留?)。
        var builtin = new LinkedHashMap<KeyStroke, String>();
        var reserved = new java.util.HashSet<KeyStroke>();
        for (var actionId : keys.actionIds()) {
            var stroke = keys.strokeFor(actionId);
            if (stroke == null) {
                continue;
            }
            builtin.putIfAbsent(stroke, actionId);
            if (RESERVED_APP_ACTIONS.contains(actionId)) {
                reserved.add(stroke);
            }
        }
        for (var hardcoded : hardcodedReserved(windows)) {
            reserved.add(hardcoded);
        }

        var resolved = new LinkedHashMap<KeyStroke, ResolvedShortcut>();
        for (var extensionEntry : byExtension.entrySet()) {
            for (var shortcut : extensionEntry.getValue().values()) {
                var stroke = KeyIdParser.parse(shortcut.keyId());
                if (reserved.contains(stroke)) {
                    diagnostics.add("Extension shortcut '" + shortcut.keyId() + "' from "
                        + shortcut.extensionPath()
                        + " conflicts with built-in shortcut. Skipping.");
                    continue;
                }
                var builtinAction = builtin.get(stroke);
                if (builtinAction != null) {
                    diagnostics.add("Extension shortcut conflict: '" + shortcut.keyId()
                        + "' is built-in shortcut for " + builtinAction + " and "
                        + shortcut.extensionPath() + ". Using " + shortcut.extensionPath() + ".");
                }
                var existing = resolved.get(stroke);
                if (existing != null) {
                    diagnostics.add("Extension shortcut conflict: '" + shortcut.keyId()
                        + "' registered by both " + existing.extensionPath() + " and "
                        + shortcut.extensionPath() + ". Using " + shortcut.extensionPath() + ".");
                }
                resolved.put(stroke, new ResolvedShortcut(stroke, shortcut.description(),
                    shortcut.extensionPath(), shortcut.handler()));
            }
        }
        return new Resolution(List.copyOf(resolved.values()), List.copyOf(diagnostics));
    }

    /** pi 保留表中 java 已知的 app.* 动作（11 条；runner.ts:96-115 的子集）。 */
    private static final java.util.Set<String> RESERVED_APP_ACTIONS = java.util.Set.of(
        KeybindingsManager.INTERRUPT,
        KeybindingsManager.CLEAR,
        KeybindingsManager.EXIT,
        KeybindingsManager.THINKING_CYCLE,
        KeybindingsManager.MODEL_CYCLE,
        KeybindingsManager.MODEL_SELECT,
        KeybindingsManager.TOOLS_EXPAND,
        KeybindingsManager.THINKING_TOGGLE,
        KeybindingsManager.EXTERNAL_EDITOR,
        KeybindingsManager.MESSAGE_COPY,
        KeybindingsManager.FOLLOW_UP);

    /** pi 保留表中 java 无动作对应物的硬编码等价键（7 条；见类 javadoc）。 */
    private static List<KeyStroke> hardcodedReserved(boolean windows) {
        var out = new ArrayList<KeyStroke>();
        out.add(new KeyStroke("enter", false, false, false)); // tui.input.submit / tui.select.confirm
        out.add(new KeyStroke("esc", false, false, false));   // tui.select.cancel
        out.add(new KeyStroke("c", true, false, false));      // tui.input.copy
        out.add(new KeyStroke("u", true, false, false));      // tui.editor.deleteToLineEnd
        if (!windows) {
            out.add(new KeyStroke("z", true, false, false));  // app.suspend
            out.add(new KeyStroke("p", true, false, true));   // app.model.cycleBackward
        } else {
            out.add(new KeyStroke("p", false, true, false));  // app.model.cycleBackward（Windows）
        }
        return out;
    }
}
