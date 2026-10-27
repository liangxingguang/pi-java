package com.pijava.coding.agent.core;

import java.util.ArrayList;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;

/**
 * 批 26（docs/37 T9，裁决⑫）：pi {@code KeyId} 串（{@code "ctrl+shift+x"}，
 * core/key-id-schema.ts 的 modifiers = ctrl/shift/alt/super）→ {@link KeyStroke}。
 *
 * <p>规则：{@code +} 分段，前缀段是修饰旗（大小写不敏感），末段键名小写归一；
 * 未识别段/空段/重复修饰/仅修饰 ⇒ {@link IllegalArgumentException}（pi 由 typebox
 * schema 在装载期校验；java 在注册点抛——不静默）。{@code super} 修饰在
 * {@link KeyStroke} 无对应旗 ⇒ 抛错（docs/37 §5 窄差）。</p>
 */
public final class KeyIdParser {

    private KeyIdParser() {}

    /** 解析 KeyId 串；非法 ⇒ {@link IllegalArgumentException}。 */
    public static KeyStroke parse(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Invalid key id: " + keyId);
        }
        // 先切键段（尾段）：「ctrl++」的键是 "+"（pi baseKeyPattern 允许单字符键）。
        final String head;
        final String key;
        if (keyId.endsWith("++")) {
            head = keyId.substring(0, keyId.length() - 2);
            key = "+";
        } else {
            int split = keyId.lastIndexOf('+');
            head = split < 0 ? "" : keyId.substring(0, split);
            key = keyId.substring(split + 1).toLowerCase(java.util.Locale.ROOT);
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Invalid key id: " + keyId);
        }
        var modifiers = head.isEmpty()
            ? new String[0] : head.toLowerCase(java.util.Locale.ROOT).split("\\+");
        boolean ctrl = false;
        boolean alt = false;
        boolean shift = false;
        for (var modifier : modifiers) {
            switch (modifier) {
                case "ctrl" -> {
                    if (ctrl) throw new IllegalArgumentException("Invalid key id: " + keyId);
                    ctrl = true;
                }
                case "alt" -> {
                    if (alt) throw new IllegalArgumentException("Invalid key id: " + keyId);
                    alt = true;
                }
                case "shift" -> {
                    if (shift) throw new IllegalArgumentException("Invalid key id: " + keyId);
                    shift = true;
                }
                default -> throw new IllegalArgumentException(
                    "Invalid key id: " + keyId + " (unsupported modifier \"" + modifier
                        + "\"; supported: ctrl, alt, shift)");
            }
        }
        return new KeyStroke(key, ctrl, alt, shift);
    }
}
