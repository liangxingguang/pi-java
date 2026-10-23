package com.pijava.coding.agent.core;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T4，docs/28 §7-7 注记）：外部编辑器命令回落链——pi
 * extension-editor.ts:55-59 逐字：{@code settings ?? VISUAL ?? EDITOR ??
 * (win32 ? "notepad" : "nano")}（JS {@code ||} falsy ⇒ 空串也回落）。
 */
class ExternalEditorResolveTest {

    @Test
    void settingsValueWins() {
        assertThat(ExternalEditorSupport.resolveCommand("code --wait",
            Map.of("VISUAL", "vim", "EDITOR", "nano")::get, false))
            .isEqualTo("code --wait");
    }

    @Test
    void visualBeatsEditorAndPlatformFallback() {
        assertThat(ExternalEditorSupport.resolveCommand(null,
            Map.of("VISUAL", "vim", "EDITOR", "emacs")::get, false))
            .isEqualTo("vim");
        assertThat(ExternalEditorSupport.resolveCommand(null,
            Map.of("EDITOR", "emacs")::get, false))
            .isEqualTo("emacs");
        // 平台回落两臂逐字。
        assertThat(ExternalEditorSupport.resolveCommand(null, Map.<String, String>of()::get, true))
            .isEqualTo("notepad");
        assertThat(ExternalEditorSupport.resolveCommand(null, Map.<String, String>of()::get, false))
            .isEqualTo("nano");
    }

    @Test
    void blankStringsFallThrough() {
        // JS 的 || 是 falsy 语义：空串继续回落（pi 逐字）。
        assertThat(ExternalEditorSupport.resolveCommand("  ",
            Map.of("VISUAL", "", "EDITOR", "ed")::get, false))
            .isEqualTo("ed");
    }
}
