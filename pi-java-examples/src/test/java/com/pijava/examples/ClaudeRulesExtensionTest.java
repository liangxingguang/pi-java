package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P11：claude-rules——session_start 扫 {@code <cwd>/.claude/rules} 递归 .md；
 * 无规则 ⇒ before_agent_start 不改 prompt；有规则 ⇒ 追加「## Project Rules」段
 * （逐字模板）＋通知计数。
 */
class ClaudeRulesExtensionTest {

    @TempDir Path tmp;

    @Test
    void findMarkdownFilesRecursesAndFilters() throws Exception {
        var rules = Files.createDirectories(tmp.resolve("rules"));
        Files.createDirectories(rules.resolve("sub"));
        Files.writeString(rules.resolve("a.md"), "a");
        Files.writeString(rules.resolve("skip.txt"), "x");
        Files.writeString(rules.resolve("sub/b.md"), "b");
        Files.createDirectories(rules.resolve("empty"));

        assertThat(ClaudeRulesExtension.findMarkdownFiles(tmp.resolve("rules"), ""))
            .containsExactlyInAnyOrder("a.md", "sub/b.md");
        assertThat(ClaudeRulesExtension.findMarkdownFiles(tmp.resolve("missing"), ""))
            .isEmpty();
    }

    @Test
    void rulesAreAppendedToSystemPromptAndCounted() throws Exception {
        var cwd = Files.createDirectories(tmp.resolve("repo"));
        var rules = Files.createDirectories(cwd.resolve(".claude/rules"));
        Files.createDirectories(rules.resolve("deep"));
        Files.writeString(rules.resolve("style.md"), "s");
        Files.writeString(rules.resolve("deep/nested.md"), "n");

        try (var scope = ExampleFixtures.scopeExtensions(tmp, ClaudeRulesExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-cr", "ok"))) {
                var result = session.processPrompt("work", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var prompt = session.harness().getSystemPrompt();
                assertThat(prompt).contains("\n\n## Project Rules\n\n"
                    + "The following project rules are available in .claude/rules/:\n\n");
                assertThat(prompt).contains("- .claude/rules/style.md");
                assertThat(prompt).contains("- .claude/rules/deep/nested.md");
                assertThat(prompt).contains("\n\nWhen working on tasks related to these rules,"
                    + " use the read tool to load the relevant rule files for guidance.\n");
            }
        }
    }

    @Test
    void noRulesLeavesPromptAlone() throws Exception {
        var cwd = Files.createDirectories(tmp.resolve("repo"));
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ClaudeRulesExtension.class)) {
            try (var session = ExampleFixtures.newSession(cwd,
                    ExampleFixtures.textProvider("faux-cr2", "ok"))) {
                var base = session.harness().getSystemPrompt();
                var result = session.processPrompt("work", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();
                assertThat(session.harness().getSystemPrompt()).isEqualTo(base);
                assertThat(base).doesNotContain("## Project Rules");
            }
        }
    }

    @Test
    void sessionStartNotificationCountsRules() throws Exception {
        var cwd = Files.createDirectories(tmp.resolve("repo"));
        var rules = Files.createDirectories(cwd.resolve(".claude/rules"));
        Files.writeString(rules.resolve("one.md"), "1");
        Files.writeString(rules.resolve("two.md"), "2");

        try (var scope = ExampleFixtures.scopeExtensions(tmp, ClaudeRulesExtension.class)) {
            var session = ExampleFixtures.newSession(cwd,
                ExampleFixtures.textProvider("faux-cr3", "ok"));
            // session_start 已于 create 时发射（NOOP UI 下通知丢失）——fork 在新侧
            // 重发 session_start（UI 随字段携带），借此观测通知
            var ui = new ExampleFixtures.ScriptedUi();
            session.extensionUI(ui, "tui");
            var forked = session.forkCopy("obs");
            assertThat(forked).isPresent();
            var copy = forked.get();
            try (copy) {
                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Found 2 rule(s) in .claude/rules/", NotifyType.INFO));
            }
            // 原会话已被 forkCopy 内部 closeForFork 关闭，不再 close
        }
    }
}
