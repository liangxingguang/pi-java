package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.ExtensionExec;
import com.pijava.coding.agent.extension.ExtensionUI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 批 26（docs/37 T3–T8）：ctx 动作面——sendUserMessage（E5-13）、appendEntry
 * （E5-14）、setSessionName/getSessionName（E5-15）、setLabel（E5-16）、exec
 * （E5-17）、setModel/getThinkingLevel/setThinkingLevel（E5-21/22）、
 * unregisterProvider（E5-5）。
 */
class DefaultExtensionContextActionsTest {

    @TempDir
    java.nio.file.Path tmp;

    private AgentSession session;
    private DefaultExtensionContext context;

    private AgentSession newSession(boolean persistent) {
        var args = persistent
            ? ArgsParser.parse(new String[] {"--provider", "faux", "--model", "hello",
                "--session-dir", tmp.resolve("sessions").toString(), "--no-extensions"})
            : ArgsParser.parse(new String[] {"--provider", "faux", "--model", "hello",
                "--no-session", "--no-extensions"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        if (persistent) {
            session = AgentSession.create(args,
                PersistentSessionRepositories.jsonl(tmp.resolve("sessions")), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()),
                SettingsManager.withStorage(new InMemorySettingsStorage()));
        } else {
            session = AgentSession.create(args, InMemorySessionRepository.create(), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()),
                SettingsManager.withStorage(new InMemorySettingsStorage()));
        }
        context = new DefaultExtensionContext(session.services(), new SkillManager(),
            ExtensionUI.noop(), new ExtensionEventBus());
        context.bindToolControl(() -> session);
        return session;
    }

    private List<Entry> transcript() {
        return session.harness().snapshot(session.laneName()).transcript();
    }

    // VT 异步起跑 ⇒ 轮询（真实条件断言，不赌时序——docs/04 §7 教训）。
    private static void awaitTrue(java.util.function.BooleanSupplier condition) {
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 30s");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
    }

    // ── T3：sendUserMessage ──

    @Test
    void sendUserMessageTextRunsATurn() throws Exception {
        try (var s = newSession(false)) {
            context.sendUserMessage("hello from extension", null);
            // 恒触发回合（pi 文档语义）：VT 异步起跑 ⇒ 轮询等落转录。
            awaitTrue(() -> transcript().stream().anyMatch(e -> e instanceof Entry.Message m
                && "user".equals(m.message().role())
                && m.message().content().toString().contains("hello from extension")));
            var userTexts = transcript().stream()
                .filter(e -> e instanceof Entry.Message m
                    && "user".equals(m.message().role()))
                .map(e -> ((Entry.Message) e).message().content().toString())
                .toList();
            assertThat(userTexts).anyMatch(t -> t.contains("hello from extension"));
        }
    }

    @Test
    void sendUserMessageBlocksNormalizeToJoinedTextAndImages() throws Exception {
        try (var s = newSession(false)) {
            // pi :2375-2386：text 块 "\n" 拼接、图片另表随消息走。
            context.sendUserMessage(CustomMessageContent.of(List.of(
                new ContentBlock.TextContent("a"),
                new ContentBlock.ImageContent("image/png", "aGk="),
                new ContentBlock.TextContent("b"))), null);
            awaitTrue(() -> transcript().stream().anyMatch(e -> e instanceof Entry.Message m
                && "user".equals(m.message().role())));
            var userMessages = transcript().stream()
                .filter(e -> e instanceof Entry.Message m
                    && "user".equals(m.message().role()))
                .map(e -> ((Entry.Message) e).message())
                .toList();
            assertThat(userMessages).isNotEmpty();
            var content = userMessages.get(userMessages.size() - 1).content();
            assertThat(content.get(0))
                .isEqualTo(new ContentBlock.TextContent("a\nb"));
            assertThat(content.stream()
                .anyMatch(b -> b instanceof ContentBlock.ImageContent)).isTrue();
        }
    }

    // ── T4：会话元数据面 ──

    @Test
    void appendEntryLandsCustomEntry() {
        try (var s = newSession(false)) {
            context.appendEntry("progress", Map.of("pct", 42));
            assertThat(transcript().stream()
                .filter(e -> e instanceof Entry.Custom)
                .map(e -> (Entry.Custom) e))
                .anyMatch(e -> "progress".equals(e.customType())
                    && Integer.valueOf(42).equals(e.data().get("pct")));
        }
    }

    @Test
    void sessionNameRoundTripsWithNormalization() {
        try (var s = newSession(true)) {
            context.setSessionName("  my\nname  ");
            assertThat(context.getSessionName()).isEqualTo("my name");
            context.setSessionName("");
            assertThat(context.getSessionName()).isNull();
        }
    }

    @Test
    void setLabelThrowsVerbatimOnUnknownEntry() {
        try (var s = newSession(true)) {
            // pi session-manager.ts:1443-1445 文案逐字。
            assertThatThrownBy(() -> context.setLabel("nope", "x"))
                .hasMessageContaining("Entry nope not found");
        }
    }

    // ── T5：模型与思考级别面 ──

    @Test
    void setModelWithoutAuthReturnsFalse() {
        try (var s = newSession(false)) {
            // faux provider 无任何凭证（环境/存储都没有）⇒ pi 的 auth 门 ⇒ false 不抛。
            assertThat(context.setModel(com.pijava.ai.model.ModelId.of("faux", "hello")))
                .isFalse();
        }
    }

    @Test
    void thinkingLevelRoundTrip() {
        try (var s = newSession(false)) {
            context.setThinkingLevel(ModelThinkingLevel.of(new com.pijava.ai.thinking.ThinkingLevel.High()));
            assertThat(context.getThinkingLevel()).isEqualTo(ModelThinkingLevel.of(new com.pijava.ai.thinking.ThinkingLevel.High()));
        }
    }

    // ── T6：exec ──

    @Test
    void execCapturesStdoutAndExitCode() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        var result = ExtensionExec.execCommand(
            windows ? "cmd" : "sh", windows ? List.of("/c", "echo hi") : List.of("-c", "echo hi"),
            null, null);
        assertThat(result.code()).isEqualTo(0);
        assertThat(result.stdout()).contains("hi");
        assertThat(result.killed()).isFalse();
    }

    @Test
    void execSpawnFailureYieldsCodeOne() {
        var result = ExtensionExec.execCommand("definitely-not-a-real-binary-xyz", List.of(),
            null, null);
        assertThat(result.code()).isEqualTo(1);
    }

    @Test
    void execTimeoutKills() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        var result = ExtensionExec.execCommand(
            windows ? "cmd" : "sh",
            windows ? List.of("/c", "ping -n 10 127.0.0.1 >nul") : List.of("-c", "sleep 10"),
            null, new ExtensionExec.ExecOptions(null, 300L, null));
        assertThat(result.killed()).isTrue();
    }

    // ── T8：unregisterProvider ──

    @Test
    void unregisterProviderRemovesAndUnknownIsSilent() {
        try (var s = newSession(false)) {
            assertThat(context.providers().get("faux")).isPresent();
            context.unregisterProvider("faux");
            assertThat(context.providers().get("faux")).isEmpty();
            context.unregisterProvider("never-registered"); // 静默（pi 同）
        }
    }
}
