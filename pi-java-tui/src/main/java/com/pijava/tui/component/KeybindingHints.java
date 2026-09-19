package com.pijava.tui.component;

import java.util.ArrayList;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;

/**
 * 键位提示文本 —— 对齐 pi {@code components/keybinding-hints.ts#keyText}。
 *
 * <p>pi 的 {@code keyText(id)} 先读用户键位表取按键串，再把修饰键用 {@code +} 连接
 * （一个动作有多个默认绑定则用 {@code /} 连接）。{@link com.pijava.coding.agent.core.KeybindingsManager}
 * 每个动作只存一个 {@link KeyStroke}，故只有 {@code +} 这一层。</p>
 *
 * <p>⚠️ pi 的字面量串里修饰键顺序<b>不统一</b>（既有 {@code ctrl+shift+up} 也有
 * {@code shift+ctrl+o}），它的 {@code formatKeyText} 只做拆分重连、不重排 ⇒
 * 布尔三元组表达不了原串顺序。此处取固定顺序 ctrl → alt → shift；
 * 今天唯一的调用点是无修饰键的 {@code esc}，顺序不可观测。</p>
 */
public final class KeybindingHints {

    private KeybindingHints() {}

    /**
     * 展开成 {@code ctrl+shift+p} 形状。
     *
     * @param stroke 中性键位（可为 null）
     * @return 提示文本；{@code null} 或键名为空时返回空串
     */
    public static String keyText(KeyStroke stroke) {
        if (stroke == null || stroke.key() == null || stroke.key().isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>(4);
        if (stroke.ctrl()) {
            parts.add("ctrl");
        }
        if (stroke.alt()) {
            parts.add("alt");
        }
        if (stroke.shift()) {
            parts.add("shift");
        }
        parts.add(stroke.key());
        return String.join("+", parts);
    }
}
