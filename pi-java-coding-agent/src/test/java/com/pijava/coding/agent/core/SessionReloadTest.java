package com.pijava.coding.agent.core;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.core.slash.SlashContext;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionFlagOptions;
import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_SHUTDOWN;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.SESSION_START;

/**
 * 批 16（T5，docs/27 §7）：{@code /reload}——事件序（pi :3662-3664 先于 :3690）、
 * defaultTools diff 三分支（B246）、usesDefaultTools=false 整臂跳过、skills/prompts
 * 重扫不叠积、flag 值跨 reload 存活、双门/成败文案逐字、fork 继承。
 */
class SessionReloadTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    /** 探针捕获的 per-extension 上下文（getFlag 驱动口）。 */
    static final List<ExtensionContext> CAPTURED = new CopyOnWriteArrayList<>();

    /** 探针扩展：录生命周期事件＋声明一个 string flag。 */
    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "reload-probe"; }

        @Override public void register(ExtensionContext ctx) {
            CAPTURED.add(ctx);
            ctx.on(SESSION_START, (e, c) -> {
                EVENTS.add("start:" + e.reason());
                return null;
            });
            ctx.on(SESSION_SHUTDOWN, (e, c) -> {
                EVENTS.add("shutdown:" + e.reason());
                return null;
            });
            ctx.registerFlag("probe-mode", ExtensionFlagOptions.ofString(null, "off"));
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;
    private InMemorySettingsStorage settingsStorage;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
        CAPTURED.clear();
        settingsStorage = new InMemorySettingsStorage();
        var servicesDir = Files.createDirectories(tmp.resolve("META-INF/services"));
        Files.writeString(
            servicesDir.resolve("com.pijava.coding.agent.extension.PiExtension"),
            Probe.class.getName() + "\n");
        Path testClasses = Path.of(getClass().getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new URLClassLoader(
            new java.net.URL[] {tmp.toUri().toURL(), testClasses.toUri().toURL()},
            previousTccl));
    }

    @AfterEach
    void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousTccl);
    }

    private AgentSession session(String... argv) {
        var args = ArgsParser.parse(argv);
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.sequence("faux",
            List.of(reply("one"), reply("two"))));
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(settingsStorage));
    }

    private static List<com.pijava.ai.stream.StreamEvent> reply(String text) {
        var done = com.pijava.ai.message.AssistantMessage.empty()
            .withContent(List.of(new com.pijava.ai.message.ContentBlock.TextContent(text)))
            .withStopReason(com.pijava.ai.message.StopReason.STOP);
        return List.of(
            new com.pijava.ai.stream.StreamEvent.Start(
                com.pijava.ai.message.AssistantMessage.empty()),
            new com.pijava.ai.stream.StreamEvent.TextEnd(0, text, done),
            new com.pijava.ai.stream.StreamEvent.StreamDone(
                com.pijava.ai.message.StopReason.STOP, null, done));
    }

    private void seedDefaultTools(List<String> tools) {
        var global = new Settings();
        global.defaultTools = tools;
        settingsStorage.writeGlobal(global);
    }

    /** 事件序：session_shutdown{reload} 先于 session_start{reload}（pi 七步序 ①→⑦）。 */
    @Test
    void shutdownReloadFiresBeforeStartReload() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            EVENTS.clear();
            s.reload();
            assertThat(EVENTS).containsExactly("shutdown:reload", "start:reload");
        }
    }

    /** B246 分支一：新加入 defaultTools 的工具被激活（pi :3680-3683）。 */
    @Test
    void toolAddedToDefaultToolsBecomesActive() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            assertThat(s.getActiveToolNames()).doesNotContain("grep");
            seedDefaultTools(List.of("read", "bash", "edit", "write", "grep"));
            s.reload();
            assertThat(s.getActiveToolNames()).contains("grep");
        }
    }

    /** B246 分支二：从 defaultTools 移除的保持激活（pi :3678-3679 注释逐字）。 */
    @Test
    void toolRemovedFromDefaultToolsStaysActive() {
        seedDefaultTools(List.of("read", "bash", "edit", "write", "grep"));
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            assertThat(s.getActiveToolNames()).contains("grep");
            seedDefaultTools(List.of("read", "bash", "edit", "write"));
            s.reload();
            assertThat(s.getActiveToolNames())
                .as("移除的保持激活").contains("grep");
        }
    }

    /** B246 分支三：会话中 -name 修饰符移除的保持移除（修饰符照旧 apply 到新设置）。 */
    @Test
    void modifierRemovedToolStaysRemovedAcrossReload() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session",
                "--tools", "-bash")) {
            assertThat(s.getActiveToolNames()).doesNotContain("bash");
            seedDefaultTools(List.of("read", "bash", "edit", "write", "grep"));
            s.reload();
            assertThat(s.getActiveToolNames())
                .as("-bash 保持移除").doesNotContain("bash")
                .as("新增 grep 仍激活").contains("grep");
        }
    }

    /** usesDefaultTools=false（显式纯名列表 --tools）⇒ reload 完全不碰工具集。 */
    @Test
    void explicitToolListIsUntouchedByReload() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session",
                "--tools", "read,grep")) {
            assertThat(s.getActiveToolNames()).containsExactlyInAnyOrder("read", "grep");
            seedDefaultTools(List.of("read", "bash", "edit", "write", "ls"));
            s.reload();
            assertThat(s.getActiveToolNames())
                .containsExactlyInAnyOrder("read", "grep");
        }
    }

    /** skills/prompts 重扫整表替换 ⇒ 不叠积（数量不变）。 */
    @Test
    void rescanReplacesWithoutAccumulating() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            var skillsBefore = s.harness().skillManager().all().size();
            var promptsBefore = s.services().promptTemplates().size();
            s.reload();
            s.reload();
            assertThat(s.harness().skillManager().all().size()).isEqualTo(skillsBefore);
            assertThat(s.services().promptTemplates().size()).isEqualTo(promptsBefore);
        }
    }

    /** flag 值跨 reload 存活（java 不重建扩展运行时 ⇒ 共享值表天然存活）。 */
    @Test
    void flagValuesSurviveReload() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session",
                "--probe-mode", "fast")) {
            var ctx = CAPTURED.getFirst();
            assertThat(ctx.getFlag("probe-mode")).isEqualTo("fast");
            s.reload();
            assertThat(ctx.getFlag("probe-mode")).isEqualTo("fast");
        }
    }

    /** CLI 未给值 ⇒ default 生效；reload 后仍是 default。 */
    @Test
    void flagDefaultAppliesAndSurvivesReload() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            var ctx = CAPTURED.getFirst();
            assertThat(ctx.getFlag("probe-mode")).isEqualTo("off");
            s.reload();
            assertThat(ctx.getFlag("probe-mode")).isEqualTo("off");
        }
    }

    /** 双门＋成败文案逐字（pi interactive-mode.ts:6430-6518）。 */
    @Test
    void slashReloadGatesAndResultTexts() throws Exception {
        // 运行中门的确定性夹具：闸式 provider 在 createApi 里挂住，run 窗口随闩锁
        // 开闭（消掉「窗口没开/已关」两向竞态）。
        var gate = new java.util.concurrent.CountDownLatch(1);
        var args = ArgsParser.parse(new String[] {
            "--provider", "faux", "--model", "hello", "--no-session"});
        var providers = ProviderRegistry.create();
        providers.register(new GatedProvider(FauxProvider.text("ok"), gate));
        try (var s = AgentSession.create(args, InMemorySessionRepository.create(), providers,
                new ToolContext(tmp.toString(), Map.of(),
                    new DefaultShellExecutor(), new DefaultFileSystem()),
                SettingsManager.withStorage(settingsStorage))) {
            var registry = s.services().slashCommands();
            assertThat(registry.get("reload").description())
                .as("pi slash-commands.ts:42 逐字")
                .isEqualTo("Reload keybindings, extensions, skills, prompts, themes,"
                    + " and context files");
            var running = s.processPrompt("hi");
            try {
                var deadline = System.currentTimeMillis() + 5_000;
                while (!s.isRunning() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(s.isRunning()).as("run 窗口已开").isTrue();
                assertThat(registry.dispatch("/reload", SlashContext.of(s))
                        .toCompletableFuture().join())
                    .isEqualTo("Wait for the current response to finish before reloading.");
            } finally {
                gate.countDown();
                running.status();
            }
            // 成功臂文案逐字。
            assertThat(registry.dispatch("/reload", SlashContext.of(s))
                    .toCompletableFuture().join())
                .isEqualTo("Reloaded keybindings, extensions, skills, prompts, themes,"
                    + " and context files");
        }
    }

    /** createApi 挂闩锁的包装 provider（其余全委托）。 */
    private static final class GatedProvider implements com.pijava.ai.provider.Provider {
        private final com.pijava.ai.provider.Provider delegate;
        private final java.util.concurrent.CountDownLatch gate;

        GatedProvider(com.pijava.ai.provider.Provider delegate,
                      java.util.concurrent.CountDownLatch gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override public String name() { return delegate.name(); }
        @Override public String displayName() { return delegate.displayName(); }
        @Override public java.util.Set<Class<? extends com.pijava.ai.api.ProviderApi>>
                supportedApis() {
            return delegate.supportedApis();
        }
        @Override public com.pijava.ai.catalog.ModelCatalog builtinModels() {
            return delegate.builtinModels();
        }
        @Override
        public <T extends com.pijava.ai.api.ProviderApi> T createApi(Class<T> apiType,
                com.pijava.ai.api.ApiOptions options) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return delegate.createApi(apiType, options);
        }
    }

    /** fork 继承：usesDefaultTools/modifiers 经 wireSession 公共路径重算（同 args）。 */
    @Test
    void forkInheritsToolSelectionPolicy() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session",
                "--tools", "-bash")) {
            var copy = s.forkCopy("branch").orElseThrow();
            assertThat(copy.toolSelection().usesDefaultTools()).isTrue();
            assertThat(copy.toolSelection().defaultToolModifiers())
                .containsExactly("-bash");
        }
    }
}
