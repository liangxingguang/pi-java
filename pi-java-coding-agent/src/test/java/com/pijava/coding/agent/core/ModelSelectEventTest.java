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
import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.MODEL_SELECT;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.THINKING_LEVEL_SELECT;

/**
 * 批 16（T6，docs/27 §7）：{@code model_select} 事件（pi types.ts:1103-1111＋
 * agent-session.ts:2457-2469）——setModel 尾发射（source "set"，在 thinking 变更
 * 之后）、同模型不发（equals 门）、cycleModelForward 发射（source "cycle"）、
 * 无订阅者零开销快走。
 */
class ModelSelectEventTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 探针扩展：录 model_select＋thinking_level_select（序钉用）。 */
    public static final class Probe implements PiExtension {
        /** ServiceLoader 无参构造。 */
        public Probe() {}

        @Override public String name() { return "model-select-probe"; }

        @Override public void register(ExtensionContext ctx) {
            ctx.on(MODEL_SELECT, (e, c) -> {
                EVENTS.add("model_select:" + e.source() + ":"
                    + id(e.previousModel()) + "->" + id(e.model()));
                return null;
            });
            ctx.on(THINKING_LEVEL_SELECT, (e, c) -> {
                EVENTS.add("thinking_level_select:" + e.level().label());
                return null;
            });
        }

        private static String id(ModelId<?> model) {
            return model == null ? "null" : model.provider() + "/" + model.modelName();
        }
    }

    @TempDir
    Path tmp;

    private ClassLoader previousTccl;
    private InMemorySettingsStorage settingsStorage;

    @BeforeEach
    void installScopedLoader() throws Exception {
        EVENTS.clear();
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
        providers.register(FauxProvider.text("ok"));
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            SettingsManager.withStorage(settingsStorage));
    }

    /** setModel 发射 source="set"＋previous 正确；发射在 thinking 变更之后（序钉）。 */
    @Test
    void setModelEmitsAfterThinkingChange() {
        var global = new Settings();
        global.modelThinkingLevels = Map.of("faux/m2", "high");
        settingsStorage.writeGlobal(global);
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            s.setModel(ModelId.of("faux", "m2"));
            assertThat(EVENTS).containsExactly(
                "thinking_level_select:high",
                "model_select:set:faux/hello->faux/m2");
        }
    }

    /** 同模型不发射（pi modelsAreEqual 门逐字）。 */
    @Test
    void sameModelDoesNotEmit() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session")) {
            s.setModel(ModelId.of("faux", "hello"));
            assertThat(EVENTS).isEmpty();
        }
    }

    /** cycleModelForward 发射 source="cycle"（pi :2565/:2597 对位——直呼
     *  harness.setModel 绕过会话层，发射补在会话方法里）。scope 用 bundled 目录里
     *  真实存在的 id（catalog.find 过滤门）。 */
    @Test
    void cycleModelForwardEmitsCycle() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session",
                "--models", "anthropic/claude-fable-5,openai/gpt-6")) {
            s.cycleModelForward();
            assertThat(EVENTS)
                .singleElement()
                .satisfies(e -> assertThat(e)
                    .startsWith("model_select:cycle:faux/hello->"));
        }
    }

    /** 无订阅者零开销快走（--no-extensions ⇒ firer 为 null，发射点判空）。 */
    @Test
    void noExtensionsFastPath() {
        try (var s = session("--provider", "faux", "--model", "hello", "--no-session",
                "--no-extensions")) {
            s.setModel(ModelId.of("faux", "m2"));
            assertThat(s.harness().getModel()).isEqualTo(ModelId.of("faux", "m2"));
            assertThat(EVENTS).isEmpty();
        }
    }
}
