package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P9：inline-bash——{@code !{cmd}} 展开替换；整行 {@code !cmd} 不碰；
 * 非零退出＋stderr ⇒ 替换为 stderr 文本；有 UI 发汇总通知；返回 transform 带原
 * images（java 恒 null，D12）。
 */
class InlineBashExtensionTest {

    @TempDir Path tmp;

    private static java.util.List<String> userTexts(
            com.pijava.coding.agent.core.AgentSession session) {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m
                && m.message() instanceof Message.UserMessage)
            .map(e -> ExampleFixtures.textOf(
                ((Message.UserMessage) ((Entry.Message) e).message()).content()))
            .toList();
    }

    @Test
    void inlinePatternExpands() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, InlineBashExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-ib", "ok"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");

                var result = session.processPrompt("say !{echo hi} please",
                    PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(userTexts(session)).containsExactly("say hi please");
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Expanded 1 inline command(s):\n!{echo hi} -> \"hi\"", NotifyType.INFO));
            }
        }
    }

    @Test
    void wholeLineBangIsNotTouched() {
        // 直接线夹具（不进会话——整行 ! 的宿主分流不归本扩展管）
        var wired = ExampleFixtures.wireWith(null, "print", new InlineBashExtension());
        var outcome = wired.firer().input("!echo hi", "interactive", null);
        assertThat(outcome.handled()).isFalse();
        assertThat(outcome.text()).isEqualTo("!echo hi");
    }

    @Test
    void nonZeroExitWithStderrReplacesWithStderr() {
        var wired = ExampleFixtures.wireWith(null, "print", new InlineBashExtension());
        var outcome = wired.firer()
            .input("check !{ls /nonexistent_xyz_42} done", "interactive", null);
        assertThat(outcome.handled()).isFalse();
        assertThat(outcome.text()).startsWith("check ").endsWith(" done")
            .contains("nonexistent_xyz_42");
    }

    @Test
    void noPatternContinues() {
        var wired = ExampleFixtures.wireWith(null, "print", new InlineBashExtension());
        var outcome = wired.firer().input("plain text", "interactive", null);
        assertThat(outcome.handled()).isFalse();
        assertThat(outcome.text()).isEqualTo("plain text");
    }

    @Test
    void replaceFirstLiteralIsFirstOccurrenceLiteral() {
        // JS replace 字面语义（$ 不特殊、只换首个）
        assertThat(InlineBashExtension.replaceFirstLiteral("a $x b $x", "$x", "y"))
            .isEqualTo("a y b $x");
        assertThat(InlineBashExtension.replaceFirstLiteral("no match", "z", "y"))
            .isEqualTo("no match");
    }
}
