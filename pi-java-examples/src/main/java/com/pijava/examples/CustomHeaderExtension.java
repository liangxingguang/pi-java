package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.pijava.coding.agent.cli.Version;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/custom-header.ts:1-73} 移植（docs/27 W5）——
 * {@code ctx.ui.setHeader} 用 pi 吉祥物替换内置 header（logo＋键位提示）。
 *
 * <p>逐字对位：仅在 {@code ctx.mode === "tui"} 时挂槽（{@code session_start}）；
 * {@code /builtin-header} 回内置（{@code setHeader(undefined)}）。吉祥物是
 * 块字符拼图：眼睛 {@code █▌}、14 块横条、四条腿；副标题
 * {@code "   shitty coding agent"}（muted）＋{@code " v<version>"}（dim）。</p>
 *
 * <p>着色走 {@code env.colorize}（组件工厂内的真实通道）；{@code white} 在 pi 里本来
 * 就是恒等（"Use plain white"），java 同。</p>
 */
public final class CustomHeaderExtension implements PiExtension {

    /** pi L21：主块字符（逐字）。 */
    static final String BLOCK = "█";

    /** pi L22：竖向半块（瞳孔，逐字）。 */
    static final String PUPIL = "▌";

    /** pi L56：副标题（逐字，含三个前导空格）。 */
    static final String SUBTITLE = "   shitty coding agent";

    @Override
    public String name() {
        return "custom-header";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            if (!"tui".equals(c.mode())) {
                return null;
            }
            c.ui().setHeader(env -> new HeaderComponent(env));
            return null;
        });
        ctx.slashCommands().register(ExampleCommands.command("builtin-header",
            "Restore built-in header with keybinding hints",
            args -> restore(ctx)));
    }

    /** pi L69-70：回内置 header 并通知。 */
    void restore(ExtensionContext ctx) {
        ctx.ui().setHeader(null);
        ctx.ui().notify("Built-in header restored", NotifyType.INFO);
    }

    /**
     * pi L13-45：吉祥物行集（{@code ["", eyes, bar, leg×4, ""]}）。
     *
     * @param fg pi {@code theme.fg(slot, text)} 的对位（{@code env.colorize}）
     */
    static List<String> piMascot(BiFunction<String, String, String> fg) {
        var piBlue = fg.apply("accent", BLOCK.repeat(14));
        var eye = BLOCK + fg.apply("dim", PUPIL);
        var lineEyes = "     " + eye + "  " + eye;
        var lineBar = "  " + piBlue;
        var lineLeg = "     " + fg.apply("accent", BLOCK.repeat(2)) + "    "
            + fg.apply("accent", BLOCK.repeat(2));
        var lines = new ArrayList<String>(8);
        lines.add("");
        lines.add(lineEyes);
        lines.add(lineBar);
        lines.add(lineLeg);
        lines.add(lineLeg);
        lines.add(lineLeg);
        lines.add(lineLeg);
        lines.add("");
        return List.copyOf(lines);
    }

    /** pi L56：{@code muted(subtitle) + dim(" v" + VERSION)}。 */
    static String subtitle(BiFunction<String, String, String> fg, String version) {
        return fg.apply("muted", SUBTITLE) + fg.apply("dim", " v" + version);
    }

    /** pi L51-61：header 组件。 */
    private static final class HeaderComponent implements ExtensionComponent {

        private final ExtensionUiHostEnv env;

        HeaderComponent(ExtensionUiHostEnv env) {
            this.env = env;
        }

        @Override
        public List<String> render(int width) {
            var lines = new ArrayList<>(piMascot(env.colorize()));
            lines.add(subtitle(env.colorize(), Version.VERSION));
            return List.copyOf(lines);
        }

        /** header 不收键（pi 组件无 handleInput 覆写）。 */
        @Override
        public void handleInput(KeybindingsManager.KeyStroke key) {
            // 无输入面
        }
    }
}
