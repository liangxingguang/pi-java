package com.pijava.tui.component;

import java.util.ArrayList;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.tui.theme.SemanticColors;
import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.element.Element;

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

    /**
     * pi {@code keyDisplayText}（keybinding-hints.ts:38-40，docs/21 §3.7）：
     * {@link #keyText} 的每段首字母大写形（{@code "alt+up" ⇒ "Alt+Up"}）——
     * dequeue 取回提示等「展示用」键名。
     *
     * @param stroke 中性键位（可为 null）
     * @return 大写形提示文本；{@code null} 或键名为空时返回空串
     */
    public static String displayText(KeyStroke stroke) {
        var text = keyText(stroke);
        if (text.isEmpty()) {
            return text;
        }
        var parts = text.split("\\+");
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                parts[i] = Character.toUpperCase(parts[i].charAt(0)) + parts[i].substring(1);
            }
        }
        return String.join("+", parts);
    }

    /**
     * L7 W6（K18，pi {@code formatKeyText}）：{@code /} 分段、{@code +} 连接；
     * macOS 的 {@code alt} 段显示为 {@code option}；可选每段首字母大写。
     *
     * @param key        原始键串（可含 {@code /} 与 {@code +}）
     * @param capitalize 是否每段首字母大写
     * @return 格式化后的键串
     */
    public static String formatKeyText(String key, boolean capitalize) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("mac");
        var alternatives = new ArrayList<String>();
        for (String alternative : key.split("/")) {
            var parts = alternative.split("\\+");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (mac && "alt".equals(part)) {
                    part = "option";
                }
                if (capitalize && !part.isEmpty()) {
                    part = Character.toUpperCase(part.charAt(0)) + part.substring(1);
                }
                parts[i] = part;
            }
            alternatives.add(String.join("+", parts));
        }
        return String.join("/", alternatives);
    }

    /**
     * L7 W6（K18，pi {@code keyHint}）：dim 键名＋空格＋muted 描述。
     *
     * @param stroke      中性键位
     * @param description 描述文本
     * @return rich text 元素（空键位 ⇒ 只有描述）
     */
    public static Element keyHint(KeyStroke stroke, String description) {
        return richHint(keyText(stroke), description);
    }

    /**
     * L7 W6（K18，pi {@code rawKeyHint}）：原始键串（{@code formatKeyText}）＋muted 描述。
     *
     * @param key         原始键串
     * @param description 描述文本
     * @return rich text 元素
     */
    public static Element rawKeyHint(String key, String description) {
        return richHint(formatKeyText(key, false), description);
    }

    private static Element richHint(String keys, String description) {
        var spans = new ArrayList<Span>();
        if (keys != null && !keys.isEmpty()) {
            spans.add(new Span(keys, SemanticColors.dim()));
            spans.add(new Span(" ", SemanticColors.muted()));
        }
        spans.add(new Span(description == null ? "" : description, SemanticColors.muted()));
        return TamboUIAdapter.richText(Text.from(Line.from(spans)));
    }
}
