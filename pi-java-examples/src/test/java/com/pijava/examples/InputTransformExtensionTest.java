package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P7：input-transform——{@code source=extension} ⇒ continue（防递归）；
 * {@code ?quick <q>} ⇒ transform「Respond briefly in 1-2 sentences: <q>」；
 * 空 query ⇒ handled＋Usage 警告；{@code ping}/{@code time} ⇒ handled＋通知；其余 continue。
 */
class InputTransformExtensionTest {

    @TempDir Path tmp;

    /** 会话里的全部 user 消息文本（提交序）。 */
    private static List<String> userTexts(com.pijava.coding.agent.core.AgentSession session) {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m
                && m.message() instanceof Message.UserMessage)
            .map(e -> ExampleFixtures.textOf(
                ((Message.UserMessage) ((Entry.Message) e).message()).content()))
            .toList();
    }

    @Test
    void quickPrefixTransforms() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, InputTransformExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-it", "ok"))) {
                var result = session.processPrompt("?quick what is pi", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(userTexts(session))
                    .containsExactly("Respond briefly in 1-2 sentences: what is pi");
            }
        }
    }

    @Test
    void emptyQuickQueryIsHandledWithUsageWarning() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, InputTransformExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-it2", "ok"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");

                var result = session.processPrompt("?quick  ", PromptConfig.defaults());
                assertThat(result.disposition()).isEqualTo("handled");
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Usage: ?quick <question>", NotifyType.WARNING));
                assertThat(userTexts(session)).isEmpty();
            }
        }
    }

    @Test
    void pingAndTimeAreHandledWithNotifications() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, InputTransformExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-it3", "ok"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");

                assertThat(session.processPrompt("PING", PromptConfig.defaults()).disposition())
                    .isEqualTo("handled");
                assertThat(session.processPrompt("time", PromptConfig.defaults()).disposition())
                    .isEqualTo("handled");
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice("pong", NotifyType.INFO));
                // time 通知本地化（内容不可逐字断言）——存在一条非 pong 的 info 通知
                assertThat(ui.notices()).anySatisfy(n -> {
                    assertThat(n.type()).isEqualTo(NotifyType.INFO);
                    assertThat(n.message()).isNotEqualTo("pong");
                });
                assertThat(userTexts(session)).isEmpty();
            }
        }
    }

    @Test
    void extensionSourceContinuesUntouched() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, InputTransformExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-it4", "ok"))) {
                var result = session.processPrompt("?quick hi",
                    PromptConfig.extension(null, null, Boolean.FALSE));
                assertThat(result.status().exitCode()).isZero();
                assertThat(userTexts(session)).containsExactly("?quick hi");
            }
        }
    }

    @Test
    void ordinaryTextContinuesUntouched() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, InputTransformExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-it5", "ok"))) {
                var result = session.processPrompt("hello there", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(userTexts(session)).containsExactly("hello there");
            }
        }
    }
}
