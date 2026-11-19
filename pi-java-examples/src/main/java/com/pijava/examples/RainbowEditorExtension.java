package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/rainbow-editor.ts:1-88} 移植（docs/27 W5）——
 * {@code setEditorComponent} 把编辑器里的 {@code ultrathink} 逐字染成彩虹并做闪烁。
 *
 * <p>逐字对位：七色基色、{@code brighten(base, factor)}（向白色插值取整）、
 * 三位闪光（中心 0.7／邻位 0.35）、20 帧循环（前 10 帧扫过、后 10 帧暂停）。
 * 文本含 {@code ultrathink}（大小写不限）时启动动画，否则停止。</p>
 *
 * <p>定时器沿 D246 先例：不建真 {@code setInterval}，用可注入的 {@link TickDriver}
 * 手动驱动帧号；渲染始终按当前帧号取值，故帧序列可断言。</p>
 */
public final class RainbowEditorExtension implements PiExtension {

    /** pi L10-18：七色基色（coral→yellow→green→teal→blue→purple→pink）。 */
    static final int[][] COLORS = {
        {233, 137, 115}, {228, 186, 103}, {141, 192, 122}, {102, 194, 179},
        {121, 157, 207}, {157, 134, 195}, {206, 130, 172}};

    /** pi L19：复位（逐字）。 */
    static final String RESET = "\u001b[0m";

    /** pi L60：帧间隔毫秒（逐字）。 */
    static final int FRAME_INTERVAL_MS = 60;

    /** pi L78：循环长度（10 帧扫光＋10 帧暂停）。 */
    static final int CYCLE_FRAMES = 20;

    /** pi L79：扫光帧数；其余帧 {@code shinePos = -1}（无高亮）。 */
    static final int SHINE_FRAMES = 10;

    /** pi L49/L80：匹配词（{@code /ultrathink/gi}）。 */
    static final Pattern ULTRATHINK = Pattern.compile("ultrathink", Pattern.CASE_INSENSITIVE);

    /** 注入式 tick 驱动（D246 先例）。 */
    interface TickDriver {
        void start(Runnable tick);

        void stop();
    }

    private TickDriver driver = new TickDriver() {
        public void start(Runnable tick) { }

        public void stop() { }
    };

    @Override
    public String name() {
        return "rainbow-editor";
    }

    /** 驱动注入（测试手动推帧）。 */
    void driver(TickDriver driver) {
        this.driver = driver;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            c.ui().setEditorComponent((env, keybindings) -> new RainbowEditor(env, this));
            return null;
        });
    }

    /** pi L21-24：{@code Math.round(c + (255 - c) * factor)} 的 24bit 前景转义。 */
    static String brighten(int[] rgb, double factor) {
        var r = (int) Math.round(rgb[0] + (255 - rgb[0]) * factor);
        var g = (int) Math.round(rgb[1] + (255 - rgb[1]) * factor);
        var b = (int) Math.round(rgb[2] + (255 - rgb[2]) * factor);
        return "\u001b[38;2;" + r + ";" + g + ";" + b + "m";
    }

    /** pi L26-42：逐字染色；{@code shinePos < 0} ⇒ 无高亮。 */
    static String colorize(String text, int shinePos) {
        var out = new StringBuilder();
        var index = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            var base = COLORS[index % COLORS.length];
            var factor = 0d;
            if (shinePos >= 0) {
                var distance = Math.abs(index - shinePos);
                if (distance == 0) {
                    factor = 0.7d;
                } else if (distance == 1) {
                    factor = 0.35d;
                }
            }
            out.append(brighten(base, factor)).appendCodePoint(cp);
            index++;
            i += Character.charCount(cp);
        }
        return out + RESET;
    }

    /** pi L78-79：{@code frame} ⇒ 高亮位置（{@code -1} 为暂停帧）。 */
    static int shinePos(int frame) {
        var cycle = Math.floorMod(frame, CYCLE_FRAMES);
        return cycle < SHINE_FRAMES ? cycle : -1;
    }

    /** pi L44-82：{@code class RainbowEditor extends CustomEditor}。 */
    static final class RainbowEditor extends ExampleEditor {

        private final RainbowEditorExtension extension;
        private boolean animating;
        private int frameValue;

        RainbowEditor(ExtensionUiHostEnv env, RainbowEditorExtension extension) {
            super(ExampleEditor.colorizeOf(env));
            this.extension = extension;
            // pi L56：this.tui.requestRender()
            requestRender(env == null ? null : env.requestRender());
        }

        /** 当前帧号（测试探针）。 */
        int frame() {
            return frameValue;
        }

        /** 动画是否在跑（测试探针）。 */
        boolean animating() {
            return animating;
        }

        /** pi L48-50：{@code /ultrathink/i.test(this.getText())}。 */
        boolean hasUltrathink() {
            return ULTRATHINK.matcher(getText()).find();
        }

        /** pi L67-74：每次输入后按文本决定动画开关。 */
        @Override
        public void handleInput(KeyStroke key) {
            super.handleInput(key);
            if (hasUltrathink()) {
                startAnimation();
            } else {
                stopAnimation();
            }
        }

        /** pi L52-58：已启动则不重复起（pi 的 {@code if (this.animationTimer) return}）。 */
        void startAnimation() {
            if (animating) {
                return;
            }
            animating = true;
            extension.driver.start(() -> {
                frameValue++;
                requestRender();
            });
        }

        /** pi L60-65：停止并清计时器。 */
        void stopAnimation() {
            if (!animating) {
                return;
            }
            animating = false;
            extension.driver.stop();
        }

        /** pi L76-81：把每行里的 ultrathink 换成当前帧的彩虹串。 */
        @Override
        public List<String> render(int width) {
            var pos = shinePos(frameValue);
            var lines = new ArrayList<String>();
            for (var line : super.render(width)) {
                var matcher = ULTRATHINK.matcher(line);
                var out = new StringBuilder();
                while (matcher.find()) {
                    matcher.appendReplacement(out,
                        Matcher.quoteReplacement(colorize(matcher.group(), pos)));
                }
                matcher.appendTail(out);
                lines.add(out.toString());
            }
            return List.copyOf(lines);
        }
    }
}
