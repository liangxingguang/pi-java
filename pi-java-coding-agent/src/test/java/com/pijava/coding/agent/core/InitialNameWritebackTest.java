package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 20（B32，裁决⑧）：{@code --name} 写 session_info（pi main.ts:709-715）
 * ＋ trim-only 规范化（cli/args.ts:66-69）＋空名干净退出（错文逐字）。
 */
class InitialNameWritebackTest {

    @TempDir
    Path tmp;

    /** 持久 jsonl 仓夹具（批 14 先例）——resume 跨 create 需要真落盘。 */
    private AgentSession session(String... argv) {
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-name", List.of()));
        var handle = PersistentSessionRepositories.jsonl(tmp);
        return AgentSession.create(args, handle, providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.load(args.projectTrustOverride()));
    }

    @Test
    void nameIsPersistedAsSessionInfo() {
        try (var s = session("--session-dir", tmp.toString(),
                "--session-id", "n1", "--name", "my-run")) {
            assertThat(s.storedSessionName()).isEqualTo("my-run");
            assertThat(s.sessionName())
                .as("显示名与 stored 同源").isEqualTo("my-run");
        }
    }

    @Test
    void nameSurvivesResume() {
        try (var s = session("--session-dir", tmp.toString(),
                "--session-id", "n2", "--name", "kept")) {
            assertThat(s.storedSessionName()).isEqualTo("kept");
        }
        // resume（-c 空臂静默新建会挑它——同 cwd 唯一会话）。
        try (var s = session("--session-dir", tmp.toString(), "-c")) {
            assertThat(s.storedSessionName())
                .as("session_info 落盘 ⇒ resume 可读（pi getSessionName 同形）")
                .isEqualTo("kept");
        }
    }

    @Test
    void nameIsTrimmedOnly() {
        try (var s = session("--session-dir", tmp.toString(),
                "--session-id", "n3", "--name", "  padded  ")) {
            // pi normalizeSessionName＝仅 trim（与 /name 的换行消毒是两个规范化器）。
            assertThat(s.storedSessionName()).isEqualTo("padded");
        }
    }

    @Test
    void blankNameExitsWithVerbatimMessage() {
        var args = ArgsParser.parse(new String[] {
            "--session-dir", tmp.toString(), "--name", "   "});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-name", List.of()));
        assertThatThrownBy(() -> AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem())))
            .isInstanceOf(SessionResolutionExit.class)
            .hasMessage("--name requires a non-empty value")
            .satisfies(e -> {
                var exit = (SessionResolutionExit) e;
                // pi：console.error("Error: …") + exit 1（前缀走 stderr）。
                assertThat(exit.code()).isEqualTo(1);
                assertThat(exit.errorPrefix()).isTrue();
            });
    }

    @Test
    void unnamedSessionHasNoStoredName() {
        try (var s = session("--session-dir", tmp.toString(), "--session-id", "n4")) {
            assertThat(s.storedSessionName()).isNull();
            assertThat(s.sessionName()).as("显示回落不变").isEqualTo("session");
        }
    }

    @Test
    void getStateWireShowsStoredNameLifecycle() throws Exception {
        // B32 线格全程（持久仓夹具）：未命名 ⇒ 键省略；命名 ⇒ 出现；清名 ⇒ 再省略。
        try (var s = session("--session-dir", tmp.toString(), "--session-id", "n5")) {
            var out = new java.io.ByteArrayOutputStream();
            var dispatcher = new com.pijava.coding.agent.rpc.RpcDispatcher(s,
                new com.pijava.coding.agent.rpc.JsonlWriter(out),
                ArgsParser.parse(new String[] {"--session-dir", tmp.toString()}));
            java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> state =
                () -> {
                    try {
                        dispatcher.handleLine("{\"id\":\"1\",\"type\":\"get_state\"}");
                        var line = out.toString().lines()
                            .filter(l -> l.contains("\"command\":\"get_state\""))
                            .reduce((a, b) -> b).orElseThrow();
                        return new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(line).get("data");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };

            assertThat(state.get().has("sessionName")).isFalse();
            s.setSessionName("wire-name");
            out.reset();
            assertThat(state.get().get("sessionName").asText()).isEqualTo("wire-name");
            s.setSessionName("");
            out.reset();
            assertThat(state.get().has("sessionName")).isFalse();
        }
    }
}
