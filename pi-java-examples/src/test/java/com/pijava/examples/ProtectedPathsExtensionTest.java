package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.PromptConfig;
import com.pijava.coding.agent.extension.NotifyType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P3：protected-paths——write/edit 写 {@code .env}/{@code .git/}/
 * {@code node_modules/} ⇒ block（reason 逐字含路径）；有 UI 时先发 warning 通知；
 * 其他工具放行。
 */
class ProtectedPathsExtensionTest {

    @TempDir Path tmp;

    @Test
    void protectedPathMatching() {
        assertThat(ProtectedPathsExtension.isProtected(".env")).isTrue();
        assertThat(ProtectedPathsExtension.isProtected("config/.env.local")).isTrue();
        assertThat(ProtectedPathsExtension.isProtected(".git/config")).isTrue();
        assertThat(ProtectedPathsExtension.isProtected("node_modules/x/index.js")).isTrue();
        assertThat(ProtectedPathsExtension.isProtected("src/main.java")).isFalse();
        assertThat(ProtectedPathsExtension.isProtected("environment.txt")).isFalse();
    }

    @Test
    void blocksProtectedWriteWithVerbatimReason() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ProtectedPathsExtension.class)) {
            var faux = ExampleFixtures.toolThenDone("faux-pp", "c1", "write",
                Map.of("path", ".env", "content", "SECRET=1"));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var result = session.processPrompt("write env", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var toolResult = firstToolResult(session);
                assertThat(toolResult.isError()).isTrue();
                assertThat(ExampleFixtures.textOf(toolResult.content()))
                    .isEqualTo("Path \".env\" is protected");
                assertThat(Files.exists(tmp.resolve(".env"))).isFalse();
            }
        }
    }

    @Test
    void warnsViaUiBeforeBlocking() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ProtectedPathsExtension.class)) {
            var faux = ExampleFixtures.toolThenDone("faux-pp2", "c1", "write",
                Map.of("path", "sub/.git/config", "content", "x"));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var ui = new ExampleFixtures.ScriptedUi();
                session.extensionUI(ui, "tui");

                var result = session.processPrompt("write git config", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                assertThat(ui.notices()).contains(
                    new ExampleFixtures.ScriptedUi.Notice(
                        "Blocked write to protected path: sub/.git/config", NotifyType.WARNING));
                assertThat(ExampleFixtures.textOf(firstToolResult(session).content()))
                    .isEqualTo("Path \"sub/.git/config\" is protected");
            }
        }
    }

    @Test
    void otherToolsAndPathsPassThrough() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, ProtectedPathsExtension.class)) {
            var faux = ExampleFixtures.toolThenDone("faux-pp3", "c1", "write",
                Map.of("path", "src/app.txt", "content", "ok"));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                var result = session.processPrompt("write file", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                assertThat(firstToolResult(session).isError()).isFalse();
                assertThat(Files.readString(tmp.resolve("src/app.txt"))).isEqualTo("ok");
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
