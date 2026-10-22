package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 12（docs/23 §3）消费点 accessor 层：{@code doubleEscapeAction} 默认回落
 * （pi settings-manager.ts:1341-1343）、{@code externalEditor} 回落链（:946-956）、
 * {@code editorPaddingX}/{@code autocompleteMaxVisible}/{@code outputPad} 的
 * 默认值与 clamp（:1373-1399 族）。
 */
class SettingsConsumptionAccessorsTest {

    private static SettingsManager manager(Path dir) {
        return SettingsManager.withStorage(new FileSettingsStorage(dir, dir));
    }

    // ── T2：doubleEscapeAction 默认回落 ─────────────────────────

    @Test
    void doubleEscapeActionFallsBackToTree(@TempDir Path dir) {
        var settings = manager(dir);
        assertThat(settings.accessors().getDoubleEscapeAction()).isEqualTo("tree");

        settings.accessors().setDoubleEscapeAction("fork");
        assertThat(settings.accessors().getDoubleEscapeAction()).isEqualTo("fork");
        settings.accessors().setDoubleEscapeAction("none");
        assertThat(settings.accessors().getDoubleEscapeAction()).isEqualTo("none");
        // pi：union 外的非法值吞掉回落默认。
        settings.accessors().setDoubleEscapeAction("bogus");
        assertThat(settings.accessors().getDoubleEscapeAction()).isEqualTo("tree");
    }

    // ── T3：externalEditor 回落链（pi :946-956 逐字四臂）────────

    @Test
    void externalEditorCommandPrefersConfigured(@TempDir Path dir) {
        var settings = manager(dir);
        settings.accessors().setExternalEditor("code --wait");
        assertThat(settings.accessors().externalEditorCommand(env -> null))
            .isEqualTo("code --wait");
    }

    @Test
    void externalEditorCommandFallsBackToVisualThenEditor(@TempDir Path dir) {
        var settings = manager(dir);
        // 配置空白 ⇒ VISUAL 优先于 EDITOR（pi :950-952）。
        settings.accessors().setExternalEditor("   ");
        assertThat(settings.accessors().externalEditorCommand(
            Map.of("VISUAL", "vim", "EDITOR", "emacs")::get)).isEqualTo("vim");
        assertThat(settings.accessors().externalEditorCommand(
            Map.of("EDITOR", "emacs")::get)).isEqualTo("emacs");
    }

    @Test
    void externalEditorCommandFallsBackToPlatformDefault(@TempDir Path dir) {
        var settings = manager(dir);
        var expected = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win") ? "notepad" : "nano";
        assertThat(settings.accessors().externalEditorCommand(env -> null))
            .isEqualTo(expected);
    }

    // ── T4：editorPaddingX ─────────────────────────────────────

    @Test
    void editorPaddingXDefaultsToZeroAndClamps(@TempDir Path dir) {
        var settings = manager(dir);
        assertThat(settings.uiPrefs().getEditorPaddingX()).isZero();

        settings.uiPrefs().setEditorPaddingX(2);
        assertThat(settings.uiPrefs().getEditorPaddingX()).isEqualTo(2);
        // pi set clamp 0..3（:1377-1380）；读侧同 clamp（双保险）。
        settings.uiPrefs().setEditorPaddingX(9);
        assertThat(settings.uiPrefs().getEditorPaddingX()).isEqualTo(3);
        settings.uiPrefs().setEditorPaddingX(-1);
        assertThat(settings.uiPrefs().getEditorPaddingX()).isZero();
    }

    // ── T5：autocompleteMaxVisible（裁决④：默认 5）──────────────

    @Test
    void autocompleteMaxVisibleDefaultsToFiveAndClamps(@TempDir Path dir) {
        var settings = manager(dir);
        assertThat(settings.uiPrefs().getAutocompleteMaxVisible()).isEqualTo(5);

        settings.uiPrefs().setAutocompleteMaxVisible(12);
        assertThat(settings.uiPrefs().getAutocompleteMaxVisible()).isEqualTo(12);
        settings.uiPrefs().setAutocompleteMaxVisible(1);
        assertThat(settings.uiPrefs().getAutocompleteMaxVisible()).isEqualTo(3);
        settings.uiPrefs().setAutocompleteMaxVisible(99);
        assertThat(settings.uiPrefs().getAutocompleteMaxVisible()).isEqualTo(20);
    }

    // ── T6：outputPad（pi :1383-1385 逐字：仅显式 0 ⇒ 0）────────

    @Test
    void outputPadDefaultsToOneUnlessExplicitZero(@TempDir Path dir) {
        var settings = manager(dir);
        assertThat(settings.uiPrefs().getOutputPad()).isEqualTo(1);

        settings.uiPrefs().setOutputPad(0);
        assertThat(settings.uiPrefs().getOutputPad()).isZero();
        // pi：0|1 字面量联合——写侧非 0 归一为 1。
        settings.uiPrefs().setOutputPad(7);
        assertThat(settings.uiPrefs().getOutputPad()).isEqualTo(1);
    }

    // ── 持久化：写后 flush 重读仍在 ─────────────────────────────

    @Test
    void consumptionKeysPersistAcrossReload(@TempDir Path dir) throws Exception {
        var settings = manager(dir);
        settings.accessors().setDoubleEscapeAction("fork");
        settings.uiPrefs().setEditorPaddingX(2);
        settings.uiPrefs().setAutocompleteMaxVisible(9);
        settings.uiPrefs().setOutputPad(0);
        settings.flush();

        var reloaded = manager(dir);
        assertThat(reloaded.accessors().getDoubleEscapeAction()).isEqualTo("fork");
        assertThat(reloaded.uiPrefs().getEditorPaddingX()).isEqualTo(2);
        assertThat(reloaded.uiPrefs().getAutocompleteMaxVisible()).isEqualTo(9);
        assertThat(reloaded.uiPrefs().getOutputPad()).isZero();
        // 具名字段不落 unknown 兜底。
        assertThat(reloaded.effective().unknown()).isEmpty();
        assertThat(Files.readString(dir.resolve("settings.json")))
            .contains("\"editorPaddingX\" : 2").contains("\"outputPad\" : 0");
    }
}
