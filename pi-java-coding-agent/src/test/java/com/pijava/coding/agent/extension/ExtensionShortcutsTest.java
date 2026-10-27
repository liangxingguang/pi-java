package com.pijava.coding.agent.extension;

import java.util.LinkedHashMap;

import com.pijava.coding.agent.core.KeyIdParser;
import com.pijava.coding.agent.core.KeybindingsManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 26（docs/37 T9，E5-6）：pi {@code getShortcuts} 冲突三规则
 * （runner.ts:673-716 逐字）＋{@link KeyIdParser}（裁决⑫）。
 */
class ExtensionShortcutsTest {

    private static LinkedHashMap<String, LinkedHashMap<String, ExtensionShortcut>> table(
            String path, String keyId) {
        var inner = new LinkedHashMap<String, ExtensionShortcut>();
        inner.put(keyId, new ExtensionShortcut(keyId, "desc", ctx -> { }, path));
        var outer = new LinkedHashMap<String, LinkedHashMap<String, ExtensionShortcut>>();
        outer.put(path, inner);
        return outer;
    }

    @Test
    void reservedBuiltinIsSkippedWithDiagnostic() {
        // esc = app.interrupt（保留表）。
        var resolution = ExtensionShortcuts.resolve(new KeybindingsManager(), false,
            table("/a.jar", "esc"));

        assertThat(resolution.shortcuts()).isEmpty();
        assertThat(resolution.diagnostics()).containsExactly(
            "Extension shortcut 'esc' from /a.jar conflicts with built-in shortcut. Skipping.");
    }

    @Test
    void nonReservedBuiltinExtensionWinsWithDiagnostic() {
        // ctrl+o = app.tools.expand——保留表内 ⇒ 跳过。
        var resolution = ExtensionShortcuts.resolve(new KeybindingsManager(), false,
            table("/a.jar", "ctrl+o"));
        assertThat(resolution.shortcuts()).isEmpty();
        assertThat(resolution.diagnostics().get(0)).contains("Skipping.");

        // 非保留内置：dequeue 键（平台相关——由键表反推 keyId，Windows/其余两臂同测）。
        var keys = new KeybindingsManager();
        var stroke = keys.strokeFor(KeybindingsManager.DEQUEUE);
        assertThat(stroke).isNotNull();
        var keyId = (stroke.ctrl() ? "ctrl+" : "") + (stroke.alt() ? "alt+" : "")
            + (stroke.shift() ? "shift+" : "") + stroke.key();
        var resolution2 = ExtensionShortcuts.resolve(keys, false, table("/a.jar", keyId));
        assertThat(resolution2.shortcuts()).hasSize(1);
        assertThat(resolution2.diagnostics().get(0)).isEqualTo(
            "Extension shortcut conflict: '" + keyId + "' is built-in shortcut for"
                + " app.message.dequeue and /a.jar. Using /a.jar.");
    }

    @Test
    void laterExtensionWinsWithDiagnostic() {
        var a = new LinkedHashMap<String, ExtensionShortcut>();
        a.put("ctrl+shift+x", new ExtensionShortcut("ctrl+shift+x", null, ctx -> { }, "/a.jar"));
        var b = new LinkedHashMap<String, ExtensionShortcut>();
        b.put("ctrl+shift+x", new ExtensionShortcut("ctrl+shift+x", "b", ctx -> { }, "/b.jar"));
        var outer = new LinkedHashMap<String, LinkedHashMap<String, ExtensionShortcut>>();
        outer.put("/a.jar", a);
        outer.put("/b.jar", b);

        var resolution = ExtensionShortcuts.resolve(new KeybindingsManager(), false, outer);

        assertThat(resolution.shortcuts()).hasSize(1);
        assertThat(resolution.shortcuts().get(0).extensionPath()).isEqualTo("/b.jar");
        assertThat(resolution.diagnostics()).containsExactly(
            "Extension shortcut conflict: 'ctrl+shift+x' registered by both /a.jar and /b.jar."
                + " Using /b.jar.");
    }

    @Test
    void hardcodedReservedEnterIsSkipped() {
        var resolution = ExtensionShortcuts.resolve(new KeybindingsManager(), false,
            table("/a.jar", "enter"));

        assertThat(resolution.shortcuts()).isEmpty();
        assertThat(resolution.diagnostics().get(0)).contains("Skipping.");
    }

    @Test
    void keyIdParserParsesModifiersAndLowercases() {
        var stroke = KeyIdParser.parse("Ctrl+Shift+P");
        assertThat(stroke.ctrl()).isTrue();
        assertThat(stroke.shift()).isTrue();
        assertThat(stroke.alt()).isFalse();
        assertThat(stroke.key()).isEqualTo("p");
    }

    @Test
    void keyIdParserRejectsBadModifierAndDuplicates() {
        assertThatThrownBy(() -> KeyIdParser.parse("super+x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyIdParser.parse("ctrl+ctrl+x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KeyIdParser.parse(""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
