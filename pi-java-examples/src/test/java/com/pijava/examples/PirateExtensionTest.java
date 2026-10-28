package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P10：pirate——/pirate 切换开关（通知文案逐字）；开启后 before_agent_start
 * 返回 systemPrompt＋海盗段（逐字）；关闭返回 null（≙undefined）。
 */
class PirateExtensionTest {

    @TempDir Path tmp;

    @Test
    void suffixIsVerbatim() {
        assertThat(PirateExtension.PIRATE_SUFFIX)
            .startsWith("\n\nIMPORTANT: You are now in PIRATE MODE. You must:\n")
            .endsWith("just in pirate speak\n")
            .contains("- Use phrases like \"Arrr!\", \"Ahoy!\", \"Shiver me timbers!\","
                + " \"Avast!\", \"Ye scurvy dog!\"\n");
    }

    @Test
    void toggleCommandNotifiesVerbatim() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PirateExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pi", "ok"))) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                var command = session.services().slashCommands().get("pirate");
                assertThat(command).isNotNull();

                command.execute("", SlashContext.of(session)).toCompletableFuture().join();
                command.execute("", SlashContext.of(session)).toCompletableFuture().join();
                assertThat(ui.notices()).containsExactly(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Arrr! Pirate mode enabled!", NotifyType.INFO),
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Pirate mode disabled", NotifyType.INFO));
            }
        }
    }

    @Test
    void enabledAppendsPirateSectionToSystemPrompt() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PirateExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pi2", "ok"))) {
                session.services().slashCommands().get("pirate")
                    .execute("", SlashContext.of(session)).toCompletableFuture().join();

                var base = session.harness().getSystemPrompt();
                var result = session.processPrompt("ahoy", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(session.harness().getSystemPrompt())
                    .isEqualTo(base + PirateExtension.PIRATE_SUFFIX);
            }
        }
    }

    @Test
    void disabledLeavesSystemPromptAlone() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PirateExtension.class)) {
            try (var session = ExampleFixtures.newSession(tmp,
                    ExampleFixtures.textProvider("faux-pi3", "ok"))) {
                var base = session.harness().getSystemPrompt();
                var result = session.processPrompt("ahoy", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(session.harness().getSystemPrompt()).isEqualTo(base);
                assertThat(base).doesNotContain("PIRATE MODE");
            }
        }
    }
}
