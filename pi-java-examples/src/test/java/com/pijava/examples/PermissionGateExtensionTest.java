package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P2：permission-gate——tool_call 拦 {@code rm -rf}/{@code sudo}/
 * {@code chmod 777}；无 UI 默认 block（reason 逐字）；有 UI 选 No ⇒ block
 * 「Blocked by user」，选 Yes ⇒ 放行；非 bash 工具不发言。
 */
class PermissionGateExtensionTest {

    @TempDir Path tmp;

    @Test
    void dangerousPatterns() {
        assertThat(PermissionGateExtension.isDangerous("rm -rf /tmp/x")).isTrue();
        assertThat(PermissionGateExtension.isDangerous("rm -r /tmp/x")).isTrue();
        assertThat(PermissionGateExtension.isDangerous("rm --recursive x")).isTrue();
        assertThat(PermissionGateExtension.isDangerous("sudo apt update")).isTrue();
        assertThat(PermissionGateExtension.isDangerous("chmod 777 file")).isTrue();
        assertThat(PermissionGateExtension.isDangerous("chown 777 file")).isTrue();
        assertThat(PermissionGateExtension.isDangerous("ls -la")).isFalse();
        assertThat(PermissionGateExtension.isDangerous("chmod 755 file")).isFalse();
        assertThat(PermissionGateExtension.isDangerous("rm file.txt")).isFalse();
    }

    @Test
    void blocksDangerousWithoutUi() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PermissionGateExtension.class)) {
            var faux = ExampleFixtures.toolThenDone("faux-pg", "c1", "bash",
                Map.of("command", "sudo rm -rf /"));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var result = session.processPrompt("clean", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var toolResult = firstToolResult(session);
                assertThat(toolResult.isError()).isTrue();
                assertThat(ExampleFixtures.textOf(toolResult.content()))
                    .isEqualTo("Dangerous command blocked (no UI for confirmation)");
            }
        }
    }

    @Test
    void uiNoBlocksWithUserReason() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PermissionGateExtension.class)) {
            var faux = ExampleFixtures.toolThenDone("faux-pg2", "c1", "bash",
                Map.of("command", "rm -rf build/"));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                ui.answer("No");

                var result = session.processPrompt("clean", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var toolResult = firstToolResult(session);
                assertThat(toolResult.isError()).isTrue();
                assertThat(ExampleFixtures.textOf(toolResult.content()))
                    .isEqualTo("Blocked by user");
            }
        }
    }

    @Test
    void uiYesAllowsExecution() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PermissionGateExtension.class)) {
            // 「echo sudo」命中 \bsudo\b 但执行无害（打印 sudo）
            var faux = ExampleFixtures.toolThenDone("faux-pg3", "c1", "bash",
                Map.of("command", "echo sudo"));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                ui.answer("Yes");

                var result = session.processPrompt("run", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var toolResult = firstToolResult(session);
                assertThat(toolResult.isError()).isFalse();
                assertThat(ExampleFixtures.textOf(toolResult.content())).contains("sudo");
            }
        }
    }

    @Test
    void nonBashToolIsNotInterrogated() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, PermissionGateExtension.class)) {
            var faux = ExampleFixtures.toolThenDone("faux-pg4", "c1", "write",
                Map.of("path", "ordinary.txt", "content", "sudo rm -rf /"));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");
                // 不脚本任何应答——write 不触发 select（触发则 answers 空 ⇒ 取消 ⇒ block）

                var result = session.processPrompt("write", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var toolResult = firstToolResult(session);
                assertThat(toolResult.isError()).isFalse();
                assertThat(java.nio.file.Files.readString(tmp.resolve("ordinary.txt")))
                    .isEqualTo("sudo rm -rf /");
            }
        }
    }

    private static Message.ToolResultMessage firstToolResult(
            com.pijava.coding.agent.core.AgentSession session) {
        return session.accumulatedEntries().stream()
            .filter(e -> e instanceof Entry.Message m
                && m.message() instanceof Message.ToolResultMessage)
            .map(e -> (Message.ToolResultMessage) ((Entry.Message) e).message())
            .findFirst().orElseThrow();
    }
}
