package com.pijava.tui.app;

import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.modes.InteractiveMode;
import com.pijava.tui.screen.ChatScreen;
import com.pijava.tui.util.InlineTuiShell;
import com.pijava.tui.util.TuiEventDispatcher;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接线存在性：两种附着点都要把会话事件通道真的接到<b>本会话</b>上
 * （docs/31 §8.38.4 第 1 步）。
 *
 * <p>⚠️ 本用例只证明「接上了」。「事件真的从 harness 发出来并上屏」需要一个
 * 真的 run（要 provider），此处的通道行为由 {@link SessionEventChannelTest} 以
 * 可替换事件源钉死 —— 未覆盖的那一段在 §8.38.9 如实登记。</p>
 */
class PiTuiAppSessionChannelTest {

    @Test
    void startInlineOpensTheChannelOnTheSession() throws Exception {
        var shell = InlineTuiShell.createForTest(new FakeBackend());
        var session = AgentSession.create(ArgsParser.parse(new String[] { }));
        var app = new PiTuiApp(new InteractiveMode(session), new ChatScreen(),
            new KeybindingsManager(), new TuiEventDispatcher());

        assertThat(app.sessionEvents().isOpen()).isFalse();

        app.startInline(shell);
        assertThat(app.sessionEvents().isOpen()).isTrue();

        shell.close();
        session.close();
    }
}
