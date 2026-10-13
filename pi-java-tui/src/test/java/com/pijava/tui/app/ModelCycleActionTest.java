package com.pijava.tui.app;

import java.nio.file.Path;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.TuiEventDispatcher;

import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.tui.TuiConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B137：Ctrl+P（{@link KeybindingsManager#MODEL_CYCLE}）驱动会话在 {@code --models}
 * scope 内正向轮转。改前该动作进 {@code PiTuiApp} 的 default 空分支被丢弃。
 */
class ModelCycleActionTest {

    @TempDir
    Path sessionDir;

    @Test
    void ctrlPRotatesModelWithinScope() throws Exception {
        var backend = new FakeBackend();
        var runner = ToolkitRunner.create(
            TuiConfig.builder().backend(backend).build());
        try (var session = AgentSession.create(ArgsParser.parse(new String[] {
                "--models", "gemini-2.5*",
                "--session-dir", sessionDir.toString()}))) {
            var chatScreen = new ChatScreen();
            var mode = new InteractiveMode(session);
            var dispatcher = new TuiEventDispatcher();
            var app = new PiTuiApp(mode, chatScreen,
                new KeybindingsManager(), dispatcher);
            mode.setObservers(
                entry -> dispatcher.dispatch(() -> chatScreen.onEntry(entry)),
                event -> dispatcher.dispatch(() -> chatScreen.onStreamEvent(event)));
            app.start(runner);

            var thread = Thread.startVirtualThread(() -> {
                try {
                    runner.run(app::root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(300);
            var before = session.harness().getModel();
            assertThat(before.provider()).isEqualTo("google");

            // Ctrl+P = 0x10（KeybindingsManager 默认绑定 MODEL_CYCLE）。
            backend.feed("\u0010");
            Thread.sleep(300);

            var after = session.harness().getModel();
            assertThat(after.provider()).isEqualTo("google");
            assertThat(after).isNotEqualTo(before);

            runner.quit();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
        } finally {
            runner.close();
        }
    }
}
