package com.pijava.coding.agent.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.harness.QueueMode;
import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.rpc.JsonlWriter;
import com.pijava.coding.agent.rpc.RpcDispatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包H5 步8：<b>用户入口三件</b>（{@code docs/46 §3-D6}）。
 *
 * <ol>
 *   <li>{@code settings.defaultThinkingLevel} 要真的进请求路径
 *       —— 改动前它只被 TUI/Web 读写，{@code SessionSetup.thinkingLevelFor} 只看
 *       {@code --thinking} 与模型名后缀（pi {@code agent-session.ts:1992-2005} 的第三档）。</li>
 *   <li>{@code AgentSessionEvent.ThinkingLevelChanged} 要有**生产者**
 *       —— 改动前声明了、消费了（{@code JsonEventMapper:108}）、<b>零生产者</b>。</li>
 * </ol>
 *
 * <p>⚠️ 第二档（per-model {@code settings.getModelThinkingLevel(provider,id)}）java 没有对应
 * 设置键 ⇒ <b>登记不做</b>（{@code docs/46 §7 B15-残留-5}）。</p>
 */
class ThinkingLevelEntryTest {

    @TempDir
    Path tmp;

    private record Ctx(AgentSession session, com.pijava.coding.agent.cli.Args args) {}

    /**
     * 在临时 user.home 下写 settings.json 后建会话。
     *
     * <p>{@code FileSettingsStorage} 在构造时捕获 {@code user.home}，而构造发生在
     * {@code AgentSession.create} 里 ⇒ 属性窗口只包住 create（与 {@code RpcDispatcherTest} 同法）。</p>
     */
    private Ctx context(String defaultThinkingLevel) throws Exception {
        var home = Files.createTempDirectory("pi-java-h5-home");
        var agentDir = home.resolve(".pi-java").resolve("agent");
        Files.createDirectories(agentDir);
        if (defaultThinkingLevel != null) {
            Files.writeString(agentDir.resolve("settings.json"),
                "{\"defaultThinkingLevel\":\"" + defaultThinkingLevel + "\"}");
        }
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-entry", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-entry", List.of(List.of())));

        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
            return new Ctx(session, args);
        } finally {
            if (savedHome != null) {
                System.setProperty("user.home", savedHome);
            } else {
                System.clearProperty("user.home");
            }
        }
    }

    // ── ① settings.defaultThinkingLevel 进请求路径 ──────────────────────

    /** 没有 {@code --thinking} 时，会话的思考级别取**全局默认**。 */
    @Test
    void defaultThinkingLevelFromSettingsReachesTheSession() throws Exception {
        var session = context("high").session();

        assertThat(session.harness().getThinkingLevel())
            .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.High()));
    }

    /** 没有设置 ⇒ 仍然 {@code off}（不发明默认值）。 */
    @Test
    void absentDefaultStaysOff() throws Exception {
        var session = context(null).session();

        assertThat(session.harness().getThinkingLevel())
            .isInstanceOf(ModelThinkingLevel.Off.class);
    }

    /** {@code --thinking} 仍然**优先于**全局默认（pi 的第一档）。 */
    @Test
    void explicitCliFlagBeatsTheDefault() throws Exception {
        var home = Files.createTempDirectory("pi-java-h5-home2");
        var agentDir = home.resolve(".pi-java").resolve("agent");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("settings.json"),
            "{\"defaultThinkingLevel\":\"high\"}");
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux-entry", "--model", "hello", "--no-session",
            "--thinking", "low"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux-entry", List.of(List.of())));

        String savedHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            var session = AgentSession.create(args, providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()));
            assertThat(session.harness().getThinkingLevel())
                .isEqualTo(ModelThinkingLevel.of(new ThinkingLevel.Low()));
        } finally {
            if (savedHome != null) {
                System.setProperty("user.home", savedHome);
            } else {
                System.clearProperty("user.home");
            }
        }
    }

    // ── ② thinking_level_changed 的生产者 ──────────────────────────────

    /** RPC 改级别 ⇒ 事件流里出现 {@code thinking_level_changed}（pi agent-session.ts:1955）。 */
    @Test
    void changingTheLevelEmitsThinkingLevelChanged() throws Exception {
        var ctx = context(null);
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        dispatcher.handleLine(
            "{\"id\":\"1\",\"type\":\"set_thinking_level\",\"level\":\"high\"}");

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"type\":\"thinking_level_changed\"");
        assertThat(output).contains("\"level\":\"high\"");
    }

    /** 级别**没变** ⇒ 不发事件（pi 的 {@code isChanging} 门）。 */
    @Test
    void settingTheSameLevelEmitsNothing() throws Exception {
        var ctx = context(null);
        var out = new ByteArrayOutputStream();
        var dispatcher = new RpcDispatcher(ctx.session(), new JsonlWriter(out), ctx.args());

        // 先设成 high（会发一次），清空输出后再设一次 high
        dispatcher.handleLine(
            "{\"id\":\"1\",\"type\":\"set_thinking_level\",\"level\":\"high\"}");
        out.reset();
        dispatcher.handleLine(
            "{\"id\":\"2\",\"type\":\"set_thinking_level\",\"level\":\"high\"}");

        assertThat(out.toString(StandardCharsets.UTF_8))
            .doesNotContain("thinking_level_changed");
    }
}
