package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：rainbow-editor 验收——基色/插入取整、三位闪光、20 帧循环、动画起停。 */
class RainbowEditorExtensionTest {

    private static final int[][] COLORS = RainbowEditorExtension.COLORS;

    private static ExtensionUiHostEnv env(AtomicInteger renders) {
        return new ExtensionUiHostEnv(() -> 80, () -> 24, renders::incrementAndGet,
            (slot, text) -> text);
    }

    @Test
    void baseColorsArePiLiterals() {
        assertThat(COLORS.length).isEqualTo(7);
        assertThat(COLORS[0]).containsExactly(233, 137, 115);
        assertThat(COLORS[6]).containsExactly(206, 130, 172);
        assertThat(RainbowEditorExtension.RESET).isEqualTo("\u001b[0m");
        assertThat(RainbowEditorExtension.FRAME_INTERVAL_MS).isEqualTo(60);
    }

    @Test
    void brightenInterpolatesTowardWhite() {
        assertThat(RainbowEditorExtension.brighten(new int[] {233, 137, 115}, 0))
            .isEqualTo("\u001b[38;2;233;137;115m");
        assertThat(RainbowEditorExtension.brighten(new int[] {233, 137, 115}, 0.7))
            .isEqualTo("\u001b[38;2;248;220;213m");
        assertThat(RainbowEditorExtension.brighten(new int[] {0, 0, 0}, 1))
            .isEqualTo("\u001b[38;2;255;255;255m");
    }

    @Test
    void colorizeUsesThreeCharacterShine() {
        // shinePos=0：中心 0.7、邻位 0.35、其余原色
        assertThat(RainbowEditorExtension.colorize("ab", 0)).isEqualTo(
            "\u001b[38;2;248;220;213ma\u001b[38;2;237;210;156mb" + RainbowEditorExtension.RESET);
        // 无高亮帧
        assertThat(RainbowEditorExtension.colorize("a", -1)).isEqualTo(
            "\u001b[38;2;233;137;115ma" + RainbowEditorExtension.RESET);
        // 第八个字符回到首色（七色循环，下标 7 % 7 = 0）
        var eight = RainbowEditorExtension.colorize("abcdefgh", -1);
        assertThat(eight).contains("\u001b[38;2;233;137;115mh");
        assertThat(eight).contains("\u001b[38;2;206;130;172mg");
    }

    @Test
    void shinePositionCyclesTenOnTenOff() {
        assertThat(RainbowEditorExtension.shinePos(0)).isZero();
        assertThat(RainbowEditorExtension.shinePos(9)).isEqualTo(9);
        assertThat(RainbowEditorExtension.shinePos(10)).isEqualTo(-1);
        assertThat(RainbowEditorExtension.shinePos(19)).isEqualTo(-1);
        assertThat(RainbowEditorExtension.shinePos(20)).isZero();
    }

    @Test
    void ultrathinkStartsAnimationAndRemovalStopsIt() {
        var renders = new AtomicInteger();
        var ticks = new ArrayList<Runnable>();
        var w = new W3Context();
        var ext = new RainbowEditorExtension();
        ext.driver(new RainbowEditorExtension.TickDriver() {
            public void start(Runnable tick) {
                ticks.add(tick);
            }

            public void stop() {
                ticks.clear();
            }
        });
        ext.register(w.ctx);
        w.fire(ExtensionEventSpec.SESSION_START, null);

        var editor = (RainbowEditorExtension.RainbowEditor) w.ui.editorFactories().get(0)
            .create(env(renders), new KeybindingsManager());

        "ultrathink".chars().forEach(c -> editor.handleInput(
            KeyStroke.of(String.valueOf((char) c), false, false, false)));

        assertThat(editor.hasUltrathink()).isTrue();
        assertThat(editor.animating()).isTrue();
        assertThat(ticks).hasSize(1);

        ticks.get(0).run();
        assertThat(editor.frame()).isEqualTo(1);
        assertThat(renders.get()).isEqualTo(1);
        assertThat(editor.render(40).get(1)).contains("\u001b[38;2;");

        // 擦掉整词 ⇒ 停止动画
        for (int i = 0; i < "ultrathink".length(); i++) {
            editor.handleInput(KeyStroke.of("backspace", false, false, false));
        }
        assertThat(editor.hasUltrathink()).isFalse();
        assertThat(editor.animating()).isFalse();
        assertThat(ticks).isEmpty();
    }

    @Test
    void renderReplacesWordCaseInsensitively() {
        var w = new W3Context();
        var ext = new RainbowEditorExtension();
        ext.register(w.ctx);
        w.fire(ExtensionEventSpec.SESSION_START, null);

        var editor = (RainbowEditorExtension.RainbowEditor) w.ui.editorFactories().get(0)
            .create(env(new AtomicInteger()), new KeybindingsManager());
        "UltraThink".chars().forEach(c -> editor.handleInput(
            KeyStroke.of(String.valueOf((char) c), false, false, false)));

        assertThat(editor.render(40).get(1)).contains("\u001b[38;2;");
        assertThat(editor.render(40).get(1)).doesNotContain("UltraThink");
    }
}
