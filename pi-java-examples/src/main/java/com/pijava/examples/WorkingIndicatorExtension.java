package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.WorkingIndicatorOptions;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/working-indicator.ts:1-123} 移植（docs/27 W5）——
 * {@code ctx.ui.setWorkingIndicator} 切换流式加载指示器。
 *
 * <p>逐字对位：五种模式（dot/none/pulse/spinner/default）＋ {@code /working-indicator}
 * 命令的四臂（无参数报当前、非法参数报用法、合法参数施加、{@code reset} 回默认）。
 * 帧自带 24bit ANSI（pi 注释：颜色须扩展自带）；状态段文案 {@code "Indicator: ..."}。</p>
 *
 * <p>窄差见 {@link ThemeText}（docs/27 §3.4）：状态段的 {@code theme.fg} 只剩文本。</p>
 */
public final class WorkingIndicatorExtension implements PiExtension {

    /** pi L23：旋转帧（逐字）。 */
    static final List<String> SPINNER_FRAMES =
        List.of("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏");

    /** pi L24-31：粉彩色谱（前六色都是 24bit 前景转义）。 */
    static final List<String> PASTEL_RAINBOW = List.of(
        "\u001b[38;2;255;179;186m",
        "\u001b[38;2;255;223;186m",
        "\u001b[38;2;255;255;186m",
        "\u001b[38;2;186;255;201m",
        "\u001b[38;2;186;225;255m",
        "\u001b[38;2;218;186;255m");

    /** pi L32：前景复位（逐字）。 */
    static final String RESET_FG = "\u001b[39m";

    /** pi L21：模式闭集（纯常量 ⇒ enum）。 */
    enum Mode {
        DOT("dot"), NONE("none"), PULSE("pulse"), SPINNER("spinner"), DEFAULT("default");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        /** pi 的命令参数名（{@code "default"} 是 {@code reset} 的落点，不可直接输入）。 */
        String wire() {
            return wire;
        }

        /** 解析命令参数；非法 ⇒ {@code null}。 */
        static Mode parse(String value) {
            for (var mode : values()) {
                if (mode.wire.equals(value)) {
                    return mode;
                }
            }
            return null;
        }
    }

    private Mode mode = Mode.SPINNER;

    @Override
    public String name() {
        return "working-indicator";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            applyIndicator(c);
            return null;
        });
        ctx.slashCommands().register(ExampleCommands.command("working-indicator",
            "Set the streaming working indicator: dot, pulse, none, spinner, or reset.",
            args -> onCommand(ctx, args)));
    }

    /** pi L37-39：{@code `${color}${text}\x1b[39m`}。 */
    static String colorize(String text, String color) {
        return color + text + RESET_FG;
    }

    /** pi L33-35：空帧表＝整体隐藏。 */
    static WorkingIndicatorOptions hiddenIndicator() {
        return new WorkingIndicatorOptions(List.of(), null);
    }

    /** pi L41-69：模式 ⇒ 指示器选项；{@code default} ⇒ {@code undefined}（java 的 null）。 */
    static WorkingIndicatorOptions indicator(Mode mode) {
        return switch (mode) {
            case DOT -> new WorkingIndicatorOptions(
                List.of(colorize("●", PASTEL_RAINBOW.get(0))), null);
            case NONE -> hiddenIndicator();
            case PULSE -> new WorkingIndicatorOptions(List.of(
                colorize("·", PASTEL_RAINBOW.get(0)),
                colorize("•", PASTEL_RAINBOW.get(2)),
                colorize("●", PASTEL_RAINBOW.get(4)),
                colorize("•", PASTEL_RAINBOW.get(5))), 120L);
            case SPINNER -> new WorkingIndicatorOptions(spinnerFrames(), 80L);
            case DEFAULT -> null;
        };
    }

    /** pi L61-63：{@code SPINNER_FRAMES.map((frame, i) => colorize(frame, PASTEL[i % 6]))}。 */
    static List<String> spinnerFrames() {
        var frames = new ArrayList<String>(SPINNER_FRAMES.size());
        for (int i = 0; i < SPINNER_FRAMES.size(); i++) {
            frames.add(colorize(SPINNER_FRAMES.get(i),
                PASTEL_RAINBOW.get(i % PASTEL_RAINBOW.size())));
        }
        return List.copyOf(frames);
    }

    /** pi L71-84：模式描述（通知与状态段共用的文案）。 */
    static String describeMode(Mode mode) {
        return switch (mode) {
            case DOT -> "static dot";
            case NONE -> "hidden";
            case PULSE -> "custom pulse";
            case SPINNER -> "custom spinner";
            case DEFAULT -> "pi default spinner";
        };
    }

    /** pi L89-92：{@code setWorkingIndicator} ＋ 状态段。 */
    void applyIndicator(ExtensionContext ctx) {
        ctx.ui().setWorkingIndicator(indicator(mode));
        ctx.ui().setStatus("working-indicator",
            ThemeText.fg("dim", "Indicator: " + describeMode(mode)));
    }

    /** pi L100-121：命令四臂。 */
    void onCommand(ExtensionContext ctx, String args) {
        var nextMode = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        if (nextMode.isEmpty()) {
            ctx.ui().notify("Working indicator: " + describeMode(mode), NotifyType.INFO);
            return;
        }
        if (!isAccepted(nextMode)) {
            ctx.ui().notify("Usage: /working-indicator [dot|pulse|none|spinner|reset]",
                NotifyType.ERROR);
            return;
        }
        mode = "reset".equals(nextMode) ? Mode.DEFAULT : Mode.parse(nextMode);
        applyIndicator(ctx);
        ctx.ui().notify("Working indicator set to: " + describeMode(mode), NotifyType.INFO);
    }

    /** pi L107-116：四个合法参数＋{@code reset}（{@code default} 不可直达）。 */
    private static boolean isAccepted(String value) {
        return "dot".equals(value) || "none".equals(value) || "pulse".equals(value)
            || "spinner".equals(value) || "reset".equals(value);
    }

    /** 当前模式（测试探针）。 */
    Mode mode() {
        return mode;
    }
}
