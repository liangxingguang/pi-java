package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.Args;
import com.pijava.coding.agent.cli.ArgsParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 11 T2（docs/22 §3.2/§3.3，pi {@code model-resolver.ts:666-685} ＋
 * {@code agent-session.ts:2669-2681}）：思考档优先级
 * <b>CLI &gt; model 后缀 &gt; scope 显式 &gt; per-model &gt; 全局默认</b>；
 * {@code AgentSession.setModel}/{@code cycleModelForward} 的 per-model 解析；
 * 无配置时继承当前档（不写 entry —— 窄差 D54 钉死）。
 */
class ThinkingLevelForModelSwitchTest {

    private static ProviderRegistry fauxProviders() {
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        return providers;
    }

    private static ToolContext toolContext(Path cwd) {
        return new ToolContext(cwd.toString(), Map.of(),
            new DefaultShellExecutor(), new DefaultFileSystem());
    }

    private interface HomeRun {
        void run() throws Exception;
    }

    private static void withTempHome(Path home, HomeRun body) throws Exception {
        String saved = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            body.run();
        } finally {
            System.setProperty("user.home", saved);
        }
    }

    private static String label(ModelThinkingLevel level) {
        return level instanceof ModelThinkingLevel.Enabled e ? e.level().label() : "off";
    }

    // ── thinkingLevelFor 五臂（纯函数面） ─────────────────────

    @Test
    void cliFlagBeatsEverything() {
        Args args = ArgsParser.parse(new String[] {"--thinking", "high", "--model", "m:max"});

        var level = SessionSetup.thinkingLevelFor(args, "medium", "low", "minimal");

        assertThat(label(level)).isEqualTo("high");
    }

    @Test
    void modelSuffixBeatsScopePerModelAndDefault() {
        Args args = ArgsParser.parse(new String[] {"--model", "some-model:max"});

        var level = SessionSetup.thinkingLevelFor(args, "medium", "low", "high");

        assertThat(label(level)).isEqualTo("max");
    }

    @Test
    void scopeExplicitBeatsPerModelAndDefault() {
        Args args = ArgsParser.parse(new String[0]);

        var level = SessionSetup.thinkingLevelFor(args, "medium", "low", "high");

        assertThat(label(level)).isEqualTo("low");
    }

    /** 新增臂（B15-残留-5 销号）：per-model 在 scope 之后、全局默认之前。 */
    @Test
    void perModelBeatsDefault() {
        Args args = ArgsParser.parse(new String[0]);

        var level = SessionSetup.thinkingLevelFor(args, "medium", null, "high");

        assertThat(label(level)).isEqualTo("high");
    }

    @Test
    void defaultIsLastResort() {
        Args args = ArgsParser.parse(new String[0]);

        var level = SessionSetup.thinkingLevelFor(args, "medium", null, null);

        assertThat(label(level)).isEqualTo("medium");
    }

    // ── 会话面（AgentSession.setModel / cycleModelForward） ────

    private static void writeGlobalSettings(Path home, String json) throws Exception {
        var agentDir = home.resolve(".pi-java").resolve("agent");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("settings.json"), json);
    }

    /** pi {@code setModel}（agent-session.ts:2477-2496）：per-model 覆盖随切模型应用。 */
    @Test
    void setModelAppliesPerModelLevel(@TempDir Path home) throws Exception {
        writeGlobalSettings(home,
            "{\"modelThinkingLevels\":{\"anthropic/claude-sonnet\":\"high\"}}");
        var tmp = Files.createTempDirectory("pi-b11-work");
        withTempHome(home, () -> {
            Args args = ArgsParser.parse(new String[] {
                "--no-session", "--no-skills", "--no-extensions"});
            try (var session = AgentSession.create(args, fauxProviders(), toolContext(tmp))) {
                session.setModel(ModelId.of("anthropic", "claude-sonnet"));

                assertThat(label(session.harness().getThinkingLevel())).isEqualTo("high");
            }
        });
    }

    /** 裁决⑦／窄差 D54：无 per-model、无全局默认 ⇒ 继承当前档（不动），
     *  且<b>不发</b>思考档变更事件（D54 钉死：java 不写 pi 的回落 entry）。 */
    @Test
    void setModelWithoutConfigKeepsCurrentLevel(@TempDir Path home) throws Exception {
        var tmp = Files.createTempDirectory("pi-b11-work2");
        withTempHome(home, () -> {
            Args args = ArgsParser.parse(new String[] {
                "--thinking", "low", "--no-session", "--no-skills", "--no-extensions"});
            try (var session = AgentSession.create(args, fauxProviders(), toolContext(tmp))) {
                assertThat(label(session.harness().getThinkingLevel())).isEqualTo("low");
                var events = new java.util.ArrayList<AgentSessionEvent>();
                session.subscribe(events::add);

                session.setModel(ModelId.of("anthropic", "claude-sonnet"));

                assertThat(label(session.harness().getThinkingLevel())).isEqualTo("low");
                assertThat(events)
                    .noneMatch(e -> e instanceof AgentSessionEvent.ThinkingLevelChanged);
            }
        });
    }

    /** 全局默认在 per-model 缺席时接管（pi 第三臂）。 */
    @Test
    void setModelFallsBackToGlobalDefault(@TempDir Path home) throws Exception {
        writeGlobalSettings(home, "{\"defaultThinkingLevel\":\"medium\"}");
        var tmp = Files.createTempDirectory("pi-b11-work3");
        withTempHome(home, () -> {
            Args args = ArgsParser.parse(new String[] {
                "--thinking", "low", "--no-session", "--no-skills", "--no-extensions"});
            try (var session = AgentSession.create(args, fauxProviders(), toolContext(tmp))) {
                session.setModel(ModelId.of("anthropic", "claude-sonnet"));

                assertThat(label(session.harness().getThinkingLevel())).isEqualTo("medium");
            }
        });
    }

    /** {@code cycleModelForward}：scope 显式级别 &gt; per-model（pi :2548 explicit 臂）。 */
    @Test
    void cycleModelScopeExplicitBeatsPerModel(@TempDir Path home) throws Exception {
        writeGlobalSettings(home,
            "{\"modelThinkingLevels\":{\"google/gemini-2.5-flash\":\"minimal\","
                + "\"google/gemini-2.5-pro\":\"minimal\"}}");
        var tmp = Files.createTempDirectory("pi-b11-work4");
        withTempHome(home, () -> {
            Args args = ArgsParser.parse(new String[] {
                "--models", "gemini-2.5*:max", "--no-session",
                "--no-skills", "--no-extensions"});
            try (var session = AgentSession.create(args, fauxProviders(), toolContext(tmp))) {
                session.cycleModelForward();

                assertThat(label(session.harness().getThinkingLevel())).isEqualTo("max");
            }
        });
    }

    /** {@code cycleModelForward}：scope 无显式 ⇒ per-model 接管。 */
    @Test
    void cycleModelAppliesPerModelWhenScopeSilent(@TempDir Path home) throws Exception {
        writeGlobalSettings(home,
            "{\"modelThinkingLevels\":{\"google/gemini-2.5-flash\":\"high\","
                + "\"google/gemini-2.5-pro\":\"high\"}}");
        var tmp = Files.createTempDirectory("pi-b11-work5");
        withTempHome(home, () -> {
            Args args = ArgsParser.parse(new String[] {
                "--models", "gemini-2.5*", "--no-session",
                "--no-skills", "--no-extensions"});
            try (var session = AgentSession.create(args, fauxProviders(), toolContext(tmp))) {
                session.cycleModelForward();

                assertThat(label(session.harness().getThinkingLevel())).isEqualTo("high");
            }
        });
    }

    /** 会话创建：初始模型命中 per-model 表 ⇒ 落到 harness（pi sdk.ts:262-270）。 */
    @Test
    void sessionCreateAppliesPerModelForInitialModel(@TempDir Path home) throws Exception {
        writeGlobalSettings(home,
            "{\"modelThinkingLevels\":{\"google/gemini-2.5-flash\":\"high\"}}");
        var tmp = Files.createTempDirectory("pi-b11-work6");
        withTempHome(home, () -> {
            Args args = ArgsParser.parse(new String[] {
                "--model", "gemini-2.5-flash", "--no-session",
                "--no-skills", "--no-extensions"});
            try (var session = AgentSession.create(args, fauxProviders(), toolContext(tmp))) {
                assertThat(session.harness().getModel().modelName())
                    .isEqualTo("gemini-2.5-flash");
                assertThat(label(session.harness().getThinkingLevel())).isEqualTo("high");
            }
        });
    }
}
