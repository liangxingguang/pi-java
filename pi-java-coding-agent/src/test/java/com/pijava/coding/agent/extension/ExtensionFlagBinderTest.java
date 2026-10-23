package com.pijava.coding.agent.extension;

import java.util.List;

import com.pijava.agent.skill.SkillManager;
import com.pijava.coding.agent.cli.HelpText;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 16（T1，docs/27 §7）：扩展 CLI flag 面——三态配对（pi args.ts:247-271）、
 * 绑定语义（agent-session-services.ts:82-128 逐字）、注册/读取（loader.ts:331-378）、
 * --help 段格式（args.ts:273-283）。
 */
class ExtensionFlagBinderTest {

    /** 裸上下文（无会话绑定——flag 面不需要会话）。 */
    private static DefaultExtensionContext bareContext() {
        return new DefaultExtensionContext(null, new SkillManager(),
            ExtensionUI.noop(), new ExtensionEventBus());
    }

    /** 经 per-extension 委托注册（生产路径：ExtensionManager 恒走 forExtension）。 */
    private static ExtensionContext ext(DefaultExtensionContext base, String name) {
        return base.forExtension(name);
    }

    // ── 配对（pi args.ts:247-271 三态逐字）──

    @Test
    void pairsEqualsForm() {
        assertThat(ExtensionFlagBinder.pair(List.of("--k=v")))
            .containsEntry("k", "v");
    }

    @Test
    void pairsSpaceForm() {
        assertThat(ExtensionFlagBinder.pair(List.of("--k", "v")))
            .containsEntry("k", "v");
    }

    @Test
    void pairsBareFlagAsTrue() {
        assertThat(ExtensionFlagBinder.pair(List.of("--k")))
            .containsEntry("k", Boolean.TRUE);
    }

    @Test
    void valueTokenStartingWithDashOrAtIsNotSwallowed() {
        // pi:74-76——下个 token 以 -/@ 起头 ⇒ 裸 flag 记 true，下 token 另行配对。
        assertThat(ExtensionFlagBinder.pair(List.of("--a", "--b=2", "--c", "@f")))
            .containsEntry("a", Boolean.TRUE)
            .containsEntry("b", "2")
            .containsEntry("c", Boolean.TRUE);
    }

    // ── 绑定（agent-session-services.ts:82-128）──

    @Test
    void booleanFlagIgnoresSwallowedValue() {
        var base = bareContext();
        ext(base, "ext-a").registerFlag("plan",
            ExtensionFlagOptions.ofBoolean("Start in plan mode", false));
        var diagnostics = ExtensionFlagBinder.apply(List.of("--plan", "ignored-value"), base);
        // pi :97-99 逐字：boolean 型恒写 true（解析期吞下的字符串值被忽略）。
        assertThat(diagnostics).isEmpty();
        assertThat(base.flagValues()).containsEntry("plan", Boolean.TRUE);
    }

    @Test
    void stringFlagRequiresValue() {
        var base = bareContext();
        ext(base, "ext-a").registerFlag("theme-name",
            ExtensionFlagOptions.ofString(null, null));
        var diagnostics = ExtensionFlagBinder.apply(List.of("--theme-name"), base);
        assertThat(diagnostics).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo("error");
            assertThat(d.message())
                .isEqualTo("Extension flag \"--theme-name\" requires a value");
        });
    }

    @Test
    void unknownFlagsAggregateIntoOneDiagnostic() {
        var base = bareContext();
        var diagnostics = ExtensionFlagBinder.apply(List.of("--a", "--b=x"), base);
        assertThat(diagnostics).singleElement()
            .extracting("message").isEqualTo("Unknown options: --a, --b");
    }

    @Test
    void singleUnknownFlagUsesSingularForm() {
        var diagnostics = ExtensionFlagBinder.apply(List.of("--a"), bareContext());
        assertThat(diagnostics).singleElement()
            .extracting("message").isEqualTo("Unknown option: --a");
    }

    @Test
    void cliValueWinsOverDefault() {
        var base = bareContext();
        ext(base, "ext-a").registerFlag("mode",
            ExtensionFlagOptions.ofString(null, "safe"));
        ExtensionFlagBinder.apply(List.of("--mode", "fast"), base);
        // pi：default set-if-absent（:342-348）＋绑定无条件写 CLI 值（:101-103）。
        assertThat(base.flagValues()).containsEntry("mode", "fast");
    }

    // ── 注册/读取（loader.ts:331-378）──

    @Test
    void mismatchedDefaultThrowsWithPiMessage() {
        var base = bareContext();
        assertThatThrownBy(() -> ext(base, "ext-a").registerFlag("n",
                new ExtensionFlagOptions(null, ExtensionFlag.Type.STRING, Boolean.TRUE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid default for flag \"n\": expected string, got boolean");
    }

    @Test
    void getFlagIsOwnershipGated() {
        var base = bareContext();
        ext(base, "ext-a").registerFlag("plan",
            ExtensionFlagOptions.ofBoolean(null, true));
        // pi :373-378：只能读本扩展注册的名。
        assertThat(ext(base, "ext-a").getFlag("plan")).isEqualTo(Boolean.TRUE);
        assertThat(ext(base, "ext-b").getFlag("plan")).isNull();
        assertThat(ext(base, "ext-a").getFlag("never-registered")).isNull();
    }

    @Test
    void undeclaredFlagWithNoValueReadsNull() {
        var base = bareContext();
        ext(base, "ext-a").registerFlag("s", ExtensionFlagOptions.ofString(null, null));
        assertThat(ext(base, "ext-a").getFlag("s")).isNull(); // ≙ pi undefined
    }

    @Test
    void baseContextFlagAccessThrowsWithoutOwnership() {
        var base = bareContext();
        // 与 on() 同旨：基础上下文无扩展名可归属。
        assertThatThrownBy(() -> base.registerFlag("x",
                ExtensionFlagOptions.ofBoolean(null, null)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> base.getFlag("x"))
            .isInstanceOf(IllegalStateException.class);
    }

    // ── --help 段（args.ts:273-283）──

    @Test
    void helpTextWithoutExtensionFlagsOmitsSection() {
        assertThat(HelpText.text()).doesNotContain("Extension CLI Flags");
        assertThat(HelpText.text(List.of())).doesNotContain("Extension CLI Flags");
    }

    @Test
    void helpTextListsExtensionFlagsInPiFormat() {
        var flags = List.of(
            new ExtensionFlag("plan", "Start in plan mode", ExtensionFlag.Type.BOOLEAN,
                false, "plan-mode-ext"),
            new ExtensionFlag("out", null, ExtensionFlag.Type.STRING, null, "writer-ext"));
        var text = HelpText.text(flags);
        assertThat(text).contains("\nExtension CLI Flags:\n");
        // pi 逐字：`  --name[ <value>]` padEnd(30)＋description；缺省回落 Registered by。
        assertThat(text).contains("  --plan"
            + " ".repeat(30 - "  --plan".length()) + "Start in plan mode");
        assertThat(text).contains("  --out <value>"
            + " ".repeat(30 - "  --out <value>".length()) + "Registered by writer-ext");
    }
}
