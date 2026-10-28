package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P4：confirm-destructive——session_before_switch reason=new：无 UI 直接放行；
 * 有 UI 取消 ⇒ {@code {cancel}}＋「Clear cancelled」通知；reason=resume 且存在 user
 * 消息条目 ⇒ 询问，取消 ⇒ cancel；session_before_fork 同构。
 */
class ConfirmDestructiveExtensionTest {

    @TempDir Path tmp;

    @Test
    void newSessionWithoutUiPassesThrough() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ConfirmDestructiveExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-cd", "done"))) {
                assertThat(session.fireBeforeSwitch("new", null)).isFalse();
            }
        }
    }

    @Test
    void newSessionCancelledByUser() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ConfirmDestructiveExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-cd2", "done"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                ui.confirmAnswer(false);

                assertThat(session.fireBeforeSwitch("new", null)).isTrue();
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice("Clear cancelled", NotifyType.INFO));
            }
        }
    }

    @Test
    void newSessionConfirmedPasses() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ConfirmDestructiveExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-cd3", "done"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                ui.confirmAnswer(true);

                assertThat(session.fireBeforeSwitch("new", null)).isFalse();
                assertThat(ui.notices()).isEmpty();
            }
        }
    }

    @Test
    void resumeWithoutUserMessagesDoesNotAsk() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ConfirmDestructiveExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-cd4", "done"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                // 无 user 消息条目 ⇒ 不问（answers 留空也放行；
                // confirm 被调则 poll 出 null ⇒ 取消）
                assertThat(session.fireBeforeSwitch("resume", "target")).isFalse();
                assertThat(ui.notices()).isEmpty();
            }
        }
    }

    @Test
    void resumeWithUserMessageAsksAndCancels() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ConfirmDestructiveExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-cd5", "done"))) {
                session.processPrompt("some work", PromptConfig.defaults()).status();

                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                ui.confirmAnswer(false);

                assertThat(session.fireBeforeSwitch("resume", "target")).isTrue();
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice("Switch cancelled", NotifyType.INFO));
            }
        }
    }

    @Test
    void forkCancelledByUser() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ConfirmDestructiveExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-cd6", "done"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                ui.answer("No, stay in current session");

                assertThat(session.forkCopy("branch")).isEmpty();
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice("Fork cancelled", NotifyType.INFO));
            }
        }
    }
}
