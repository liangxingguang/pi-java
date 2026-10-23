package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.DefaultShellExecutor;
import com.pijava.agent.tool.ToolContext;
import com.pijava.ai.provider.FauxProvider;
import com.pijava.ai.provider.ProviderRegistry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.session.InMemorySessionRepository;
import com.pijava.coding.agent.extension.ExtensionUI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T7，docs/28 §7-5）：{@link AgentSession} 的扩展 UI 装配面——
 * 双参 setter（pi setUIContext(uiContext, mode) 对位）、fork 携带、
 * {@code onSessionInvalidated} 在替换路径与 reload 首步前各发一次。
 */
class AgentSessionExtensionUiTest {

    @TempDir
    Path tmp;

    /** 计数 UI（onSessionInvalidated 断言面）。 */
    private static final class CountingUI implements ExtensionUI {
        final AtomicInteger invalidations = new AtomicInteger();

        @Override
        public void onSessionInvalidated() {
            invalidations.incrementAndGet();
        }
    }

    private AgentSession session() {
        var args = ArgsParser.parse(new String[] {"--provider", "faux", "--model", "hello",
            "--no-session", "--no-extensions"});
        var providers = ProviderRegistry.create();
        providers.register(FauxProvider.text("ok"));
        return AgentSession.create(args, InMemorySessionRepository.create(), providers,
            new ToolContext(tmp.toString(), Map.of(),
                new DefaultShellExecutor(), new DefaultFileSystem()),
            // 真 home settings 继承坑（memory settings-json-test-inheritance）：夹具给空存储。
            SettingsManager.withStorage(new InMemorySettingsStorage()));
    }

    @Test
    void twoArgSetterCarriesModeAndSingleArgKeepsIt() {
        try (var s = session()) {
            assertThat(s.extensionUI()).isSameAs(ExtensionUI.NOOP);
            assertThat(s.extensionMode()).isEqualTo("print");

            var ui = new CountingUI();
            s.extensionUI(ui, "tui");
            assertThat(s.extensionUI()).isSameAs(ui);
            assertThat(s.extensionMode()).isEqualTo("tui");

            // 旧单参：mode 维持原值（docs/28 §3-T1.7）。
            var other = new CountingUI();
            s.extensionUI(other);
            assertThat(s.extensionUI()).isSameAs(other);
            assertThat(s.extensionMode()).isEqualTo("tui");

            // null 双聋（既有单参的 null 门逐字）。
            s.extensionUI(null, null);
            assertThat(s.extensionUI()).isSameAs(other);
            assertThat(s.extensionMode()).isEqualTo("tui");
        }
    }

    @Test
    void forkCarriesUiAndMode() {
        try (var s = session()) {
            var ui = new CountingUI();
            s.extensionUI(ui, "rpc");
            var forked = s.forkCopy("branch").orElseThrow();
            try {
                assertThat(forked.extensionUI()).isSameAs(ui);
                assertThat(forked.extensionMode()).isEqualTo("rpc");
            } finally {
                forked.close();
            }
            // fork 臂：旧会话关闭路径先发 onSessionInvalidated（pi「一切 swap 前复位」）。
            assertThat(ui.invalidations.get()).isEqualTo(1);
        }
    }

    @Test
    void closeForReplacementInvalidatesExtensionUi() {
        try (var s = session()) {
            var ui = new CountingUI();
            s.extensionUI(ui, "tui");
            s.closeForReplacement("new", "next-id");
            assertThat(ui.invalidations.get()).isEqualTo(1);
        }
    }

    @Test
    void reloadInvalidatesBeforeSevenStepSequence() {
        try (var s = session()) {
            var ui = new CountingUI();
            s.extensionUI(ui, "tui");
            s.reload();
            // pi handleReloadCommand:6440——resetExtensionUI 在真正 reload 之前。
            assertThat(ui.invalidations.get()).isEqualTo(1);
        }
    }

    @Test
    void createWithUiAndModeBakesAtConstruction() {
        // 批 17（T7）：create(args, ui, mode) 重载——扩展加载前 UI 已烘入字段。
        // 持久路径仓开进临时目录（--session-dir；真 home settings 只读不脏）。
        var args = ArgsParser.parse(new String[] {"--provider", "faux", "--model", "hello",
            "--no-session", "--no-extensions", "--session-dir", tmp.toString()});
        var ui = new CountingUI();
        try (var s = AgentSession.create(args, ui, "tui")) {
            assertThat(s.extensionUI()).isSameAs(ui);
            assertThat(s.extensionMode()).isEqualTo("tui");
        }
    }
}
