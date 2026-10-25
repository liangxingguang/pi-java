package com.pijava.tui.theme;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.theme.Themes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThemeApplicator}＋{@link SemanticColors} 测试（批 21/docs/32 §7）。
 */
class ThemeApplicatorTest {

    @AfterEach
    void reset() {
        Themes.resetForTests();
    }

    @Test
    void generatedTcssMapsTokensAndOmitsScreenBackground() {
        Themes.initTheme("dark", false);
        var tcss = ThemeApplicator.generateTcss(Themes.current());
        // 四映射键在场（dark.json 解析值 golden——pi 锚点实跑）
        assertThat(tcss).contains("ChatViewportElement-scrollbar-thumb");
        assertThat(tcss).contains("ChatViewportElement-scrollbar-track");
        assertThat(tcss).contains(".EditorComponent {\n    border-color: #");
        assertThat(tcss).contains(".Separator {\n    background: #");
        // 裁决⑥：.Screen/.StatusBar 底色不生成（pi 无此概念）。
        assertThat(tcss).doesNotContain(".Screen");
        assertThat(tcss).doesNotContain(".StatusBar");
        // java-only hover 双臂保留。
        assertThat(tcss).contains("#7aa2f7");
        // dark.json scrollbarTrack=okhsl(237 7% 33%) ⇒ hex（非 indexed）。
        assertThat(tcss).doesNotContain("indexed(");
    }

    @Test
    void systemThemeTcssOmitsTerminalDefaultTokens() {
        // tier-3：scrollbar/track 与 borderMuted 都是 neutral ⇒ "" 终端默认 ⇒
        // 整条规则省略（TCSS 缺席＝默认色，pi tier-3「面板无底色」同族）。
        Themes.initTheme("system", false);
        var tcss = ThemeApplicator.generateTcss(Themes.current());
        assertThat(tcss).doesNotContain("ChatViewportElement-scrollbar-track {");
        assertThat(tcss).doesNotContain("ChatViewportElement-scrollbar-thumb {");
        assertThat(tcss).doesNotContain(".EditorComponent {");
        assertThat(tcss).contains(".ChatPanel");   // 非色键保留
    }

    @Test
    void semanticColorsFollowActiveTheme() {
        Themes.initTheme("dark", false);
        var darkAccent = SemanticColors.accent();
        Themes.initTheme("light", false);
        var lightAccent = SemanticColors.accent();
        // 访问器现读活跃主题（裁决⑨）——同一调用点随切换变值。
        assertThat(darkAccent).isNotEqualTo(lightAccent);
    }

    @Test
    void engineCarriesGeneratedStylesheet() {
        Themes.initTheme("dark", false);
        var engine = ThemeApplicator.engineForCurrentTheme();
        assertThat(engine.getActiveStylesheet())
            .contains(ThemeApplicator.STYLESHEET_NAME);
        // 重应用不抛（loadStylesheet 同名替换＋reload）。
        ThemeApplicator.reapply(engine);
        assertThat(engine.getStylesheetNames()).contains(ThemeApplicator.STYLESHEET_NAME);
    }
}
