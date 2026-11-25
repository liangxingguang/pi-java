package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.skill.SkillManager;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.DefaultExtensionContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionNewSessionOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D29（pi {@code ExtensionRunner.invalidate}/{@code assertActive}，runner.ts:398,733-746；
 * runtime loader.ts:164-218）：会话替换/关闭后，扩展捕获的旧 ctx 调用宿主面即抛
 * {@link IllegalStateException}（文案与 pi 逐字）；{@code switchTo} 的原地 attach
 * 只退订通道、**不**置 stale。
 */
class ExtensionStaleContextTest {

    /** pi 默认失效文案的开头（loader.ts:204，逐字）。 */
    private static final String STALE_PREFIX =
        "This extension ctx is stale after session replacement or reload.";

    @TempDir
    Path tmp;

    private ToolContext toolContext() {
        return new ToolContext(tmp.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    private AgentSession session(InMemorySessionRepository repo, String... argv) {
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        return AgentSession.create(args, repo, providers, toolContext(),
            SettingsManager.withStorage(new InMemorySettingsStorage()));
    }

    private AgentSession inMemory(String... extra) {
        var argv = new ArrayList<String>();
        argv.add("--provider");
        argv.add("faux");
        argv.add("--model");
        argv.add("hello");
        argv.add("--no-session");
        argv.add("--no-mcp");
        argv.addAll(java.util.List.of(extra));
        return session(InMemorySessionRepository.create(), argv.toArray(String[]::new));
    }

    private static ExtensionContext.SendMessageOptions noTurn() {
        return new ExtensionContext.SendMessageOptions(Boolean.FALSE, null);
    }

    private static void sendLive(ExtensionContext ctx) {
        ctx.sendMessage("live", CustomMessageContent.of("live"), true, Map.of(), noTurn());
    }

    // ── 单元臂：状态机（首条 wins／默认文案／通道退订） ──

    @Test
    void invalidateKeepsFirstMessage() {
        var ctx = new DefaultExtensionContext(null, new SkillManager());
        ctx.invalidate("first");
        ctx.invalidate("second");
        assertThatThrownBy(ctx::mode).hasMessage("first");
    }

    @Test
    void nullMessageUsesPiDefaultText() {
        var ctx = new DefaultExtensionContext(null, new SkillManager());
        ctx.invalidate(null);
        assertThat(DefaultExtensionContext.STALE_MESSAGE).startsWith(STALE_PREFIX);
        assertThatThrownBy(ctx::mode)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(DefaultExtensionContext.STALE_MESSAGE);
    }

    @Test
    void invalidateUnsubscribesTrackedChannelHandlers() {
        var ctx = new DefaultExtensionContext(null, new SkillManager());
        var seen = new ArrayList<String>();
        ctx.events().on("ch", data -> seen.add("x"));
        ctx.events().emit("ch", 1);
        assertThat(seen).containsExactly("x");

        ctx.invalidate(null);
        ctx.events().emit("ch", 2);
        assertThat(seen).as("invalidate 的 eventBus 半＝统一退订（pi loader.ts:205-206）")
            .containsExactly("x");
    }

    // ── 会话臂：真关闭／fork／newSession ──

    @Test
    void closeInvalidatesCapturedContext() {
        var s = inMemory("--session-id", "stale-close");
        var ctx = s.extensionContext();
        assertThat(ctx).isNotNull();
        assertThatCode(ctx::mode).doesNotThrowAnyException();
        assertThatCode(() -> sendLive(ctx)).as("关闭前正常会话内可用").doesNotThrowAnyException();
        var perExt = ctx.forExtension("probe");
        assertThatCode(perExt::mode).doesNotThrowAnyException();

        s.close();

        assertThatThrownBy(ctx::ui)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith(STALE_PREFIX);
        assertThatThrownBy(() -> sendLive(ctx))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith(STALE_PREFIX);
        assertThatThrownBy(perExt::mode)
            .as("per-extension 委托同样失效（共享 base 状态）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith(STALE_PREFIX);
    }

    @Test
    void forkInvalidatesSourceContextButCopyContextWorks() {
        try (var parent = inMemory("--session-id", "stale-fork")) {
            var parentCtx = parent.extensionContext();
            assertThat(parentCtx).isNotNull();

            var copy = parent.forkCopy("branch").orElseThrow();
            try (copy) {
                assertThatThrownBy(parentCtx::mode)
                    .as("pi teardownCurrent → dispose 置 stale")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith(STALE_PREFIX);

                var copyCtx = copy.extensionContext();
                assertThat(copyCtx).isNotNull();
                assertThatCode(copyCtx::mode).doesNotThrowAnyException();
                assertThatCode(() -> sendLive(copyCtx))
                    .as("forked 会话的新 ctx 可用")
                    .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void newSessionInvalidatesOldContextAndHandsOverFreshOne() {
        var dir = tmp.resolve("sessions");
        var args = ArgsParser.parse(new String[] {
            "--session-dir", dir.toString(), "--no-mcp"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        var s = AgentSession.create(args, InMemorySessionRepository.create(), providers, toolContext());
        try (s) {
            var oldCtx = s.extensionContext();
            assertThat(oldCtx).isNotNull();
            var freshCtx = new AtomicReference<ExtensionContext>();

            var result = s.newSession(new ExtensionNewSessionOptions(null, freshCtx::set));

            assertThat(result.cancelled()).isFalse();
            assertThatThrownBy(oldCtx::mode)
                .as("newSession → closeForReplacement 置 stale")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(STALE_PREFIX);
            assertThat(freshCtx.get()).as("withSession 收到替换后的 ctx").isNotNull();
            assertThatCode(() -> freshCtx.get().mode())
                .as("withSession 的 ctx 可用（pi 推荐写法）")
                .doesNotThrowAnyException();
            freshCtx.get().shutdown(); // 清理替换会话
        }
    }

    // ── 方向性：switchTo 只退订通道，不置 stale ──

    @Test
    void switchToKeepsContextActiveAndOnlyUnsubscribesChannels() {
        var repo = InMemorySessionRepository.create();
        try (var a = session(repo, "--session-id", "stale-a", "--no-mcp");
             var b = session(repo, "--session-id", "stale-b", "--no-mcp")) {
            var ctx = a.extensionContext();
            assertThat(ctx).isNotNull();
            var seen = new ArrayList<String>();
            ctx.events().on("ch", data -> seen.add("x"));

            var result = a.switchTo("stale-b");

            assertThat(result.outcome()).isEqualTo(AgentSession.SwitchOutcome.SWITCHED);
            ctx.events().emit("ch", 1);
            assertThat(seen).as("通道退订半仍生效").isEmpty();
            assertThatCode(ctx::mode)
                .as("原地 attach 复用同一 ctx —— 不得误判 stale")
                .doesNotThrowAnyException();
            assertThatCode(() -> sendLive(ctx)).doesNotThrowAnyException();
        }
    }
}
