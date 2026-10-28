package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.event.ExtensionResults.TrustDecision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P6：project-trust——五选项映射 yes+remember／yes+remember（带 note）／yes／no／
 * undecided；无 UI ⇒ undecided；session_start 通知含 ctx.cwd 与 load 计数。
 */
class ProjectTrustExtensionTest {

    @TempDir Path tmp;

    private ExampleFixtures.ScriptedUi ui;
    private ExampleFixtures.Wired wired;

    private ExampleFixtures.Wired wire(boolean withUi) {
        ui = new ExampleFixtures.ScriptedUi();
        wired = ExampleFixtures.wireWith(withUi ? ui : null, "tui",
            new ProjectTrustExtension());
        return wired;
    }

    @Test
    void noUiVotesUndecided() {
        wire(false);
        var outcome = wired.firer().projectTrust(tmp.toString());
        assertThat(outcome.result()).isEmpty(); // undecided ⇒ 结果缺席（first-wins 穿透）
        assertThat(outcome.errors()).isEmpty();
    }

    @Test
    void fiveOptionMapping() {
        wire(true);
        var cwd = tmp.toString();

        ui.answer("Trust and remember");
        var r1 = wired.firer().projectTrust(cwd).result().orElseThrow();
        assertThat(r1.trusted()).isEqualTo(TrustDecision.YES);
        assertThat(r1.remember()).isEqualTo(Boolean.TRUE);

        ui.answer("Trust this session");
        var r2 = wired.firer().projectTrust(cwd).result().orElseThrow();
        assertThat(r2.trusted()).isEqualTo(TrustDecision.YES);
        assertThat(r2.remember()).isNull();

        ui.answer("Do not trust this session");
        var r3 = wired.firer().projectTrust(cwd).result().orElseThrow();
        assertThat(r3.trusted()).isEqualTo(TrustDecision.NO);
        assertThat(r3.remember()).isNull();

        ui.answer("Let built-in prompt decide");
        assertThat(wired.firer().projectTrust(cwd).result()).isEmpty();

        ui.answer((Object) null); // 取消 ⇒ undecided
        assertThat(wired.firer().projectTrust(cwd).result()).isEmpty();

        // 每次发射都有通知（含 event.cwd/mode/load 计数）
        assertThat(ui.notices()).allSatisfy(n -> {
            assertThat(n.type()).isEqualTo(NotifyType.INFO);
            assertThat(n.message()).startsWith("project_trust fired for " + cwd + " (mode: tui, load: ");
        });
    }

    @Test
    void noteOptionRecordsDemoNote() {
        wire(true);
        ui.answer("Trust with note and remember");
        ui.answer("my note");
        var outcome = wired.firer().projectTrust(tmp.toString()).result().orElseThrow();
        assertThat(outcome.trusted()).isEqualTo(TrustDecision.YES);
        assertThat(outcome.remember()).isEqualTo(Boolean.TRUE);
        assertThat(ui.notices()).contains(
            new ExampleFixtures.ScriptedUi.Notice("Recorded demo note: my note", NotifyType.INFO));
    }

    @Test
    void noteOptionEmptyInputReadsAsNoNote() {
        wire(true);
        ui.answer("Trust with note and remember");
        ui.answer(""); // JS 空串假值 ⇒ "No demo note entered"
        wired.firer().projectTrust(tmp.toString());
        assertThat(ui.notices()).contains(
            new ExampleFixtures.ScriptedUi.Notice("No demo note entered", NotifyType.INFO));
    }

    @Test
    void sessionStartNotifiesWithCwd() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ProjectTrustExtension.class)) {
            var scripted = new ExampleFixtures.ScriptedUi();
            var args = com.pijava.coding.agent.cli.ArgsParser.parse(new String[] {
                "--no-session", "--no-mcp"});
            try (var session = com.pijava.coding.agent.core.AgentSession.create(
                    args, scripted, "tui")) {
                assertThat(scripted.notices()).anySatisfy(n -> {
                    assertThat(n.type()).isEqualTo(NotifyType.INFO);
                    assertThat(n.message()).isEqualTo(
                        "project-trust example loaded after trust resolution in "
                            + System.getProperty("user.dir"));
                });
            }
        }
    }
}
