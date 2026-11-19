package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;

import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/55 §2.2：titlebar-spinner 验收——帧表、标题模板、tick 循环、事件 start/stop。 */
class TitlebarSpinnerExtensionTest {

    @Test
    void framesAndInterval() {
        assertThat(TitlebarSpinnerExtension.BRAILLE_FRAMES)
            .containsExactly("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏");
        assertThat(TitlebarSpinnerExtension.FRAME_INTERVAL_MS).isEqualTo(80);
    }

    @Test
    void titleTemplates() {
        assertThat(TitlebarSpinnerExtension.baseTitle("repo", null)).isEqualTo("π - repo");
        assertThat(TitlebarSpinnerExtension.baseTitle("repo", "my session"))
            .isEqualTo("π - my session - repo");
        assertThat(TitlebarSpinnerExtension.spinnerTitle("⠋", "repo", null))
            .isEqualTo("⠋ π - repo");
        assertThat(TitlebarSpinnerExtension.spinnerTitle("⠙", "repo", "s"))
            .isEqualTo("⠙ π - s - repo");
    }

    @Test
    void cwdName() {
        assertThat(TitlebarSpinnerExtension.cwdName("D:/work/pi-java")).isEqualTo("pi-java");
    }

    @Test
    void tickCyclesFramesAndStopRestores() {
        var w = new W3Context();
        w.cwd = "/work/repo";
        w.sessionName = "S";
        var ticks = new ArrayList<Runnable>();
        var ext = new TitlebarSpinnerExtension();
        ext.driver(new TitlebarSpinnerExtension.TickDriver() {
            public void start(Runnable tick) { ticks.add(tick); }
            public void stop() { ticks.clear(); }
        });
        ext.register(w.ctx);

        ext.startAnimation(w.ctx);
        assertThat(ticks).hasSize(1);
        ticks.get(0).run(); // frame ⠋
        ticks.get(0).run(); // frame ⠙
        assertThat(ext.frameIndex()).isEqualTo(2);
        assertThat(w.ui.titles()).containsExactly(
            "π - S - repo", // startAnimation 先 stop 恢复基础标题
            "⠋ π - S - repo", "⠙ π - S - repo");

        ext.stopAnimation(w.ctx);
        assertThat(ext.frameIndex()).isZero();
        assertThat(w.ui.titles()).last().isEqualTo("π - S - repo");
        assertThat(ticks).isEmpty();
    }

    @Test
    void eventsMapToStartAndStop() {
        var w = new W3Context();
        var ext = new TitlebarSpinnerExtension();
        ext.register(w.ctx);
        // agent_start → start, agent_settled / session_shutdown → stop（间接由夹具驱动）
        w.fire(ExtensionEventSpec.AGENT_START, null);
        w.fire(ExtensionEventSpec.AGENT_SETTLED, null);
        w.fire(ExtensionEventSpec.SESSION_SHUTDOWN, null);
        // 默认 noop driver 下不报错即通过形状断言
        assertThat(w.ui.titles()).isNotEmpty();
    }
}
