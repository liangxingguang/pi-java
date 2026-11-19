package com.pijava.examples;

import java.util.List;
import java.util.Locale;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.FooterDataView;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/custom-footer.ts:1-64} 移植（docs/27 W5）——
 * {@code ctx.ui.setFooter} 用扩展组件替换内置 footer。
 *
 * <p>逐字对位：{@code /footer} 开关；开启时 footer 组件按分支上的 assistant 用量
 * 汇总 {@code ↑input ↓output $cost}（左）与 {@code <model> (<git分支>)}（右），
 * 中间用空格补齐到宽度。两条 {@code theme.fg("dim", …)} 走 {@code env.colorize}
 * （组件工厂内的真实着色通道）。</p>
 *
 * <p>窄差 D100：{@code footerData.onBranchChange} 未投影（{@link FooterDataView}
 * 只有 git 分支与扩展状态表两个现读源），故 pi 的 {@code dispose: unsub} 在 java
 * 无对位——分支变化时不会主动 {@code requestRender}，下一次重绘自然反映。</p>
 */
public final class CustomFooterExtension implements PiExtension {

    /** pi L50：无模型时的占位（逐字）。 */
    static final String NO_MODEL = "no-model";

    private boolean enabled;

    @Override
    public String name() {
        return "custom-footer";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("footer",
            "Toggle custom footer", args -> toggle(ctx)));
    }

    /** pi L20-61：开关两臂。 */
    void toggle(ExtensionContext ctx) {
        enabled = !enabled;
        if (enabled) {
            ctx.ui().setFooter((env, footerData) -> footer(env, footerData, ctx));
            ctx.ui().notify("Custom footer enabled", NotifyType.INFO);
            return;
        }
        ctx.ui().setFooter(null);
        ctx.ui().notify("Default footer restored", NotifyType.INFO);
    }

    /** 开关态（测试探针）。 */
    boolean enabled() {
        return enabled;
    }

    /** pi L24-56：footer 工厂。 */
    static ExtensionComponent footer(ExtensionUiHostEnv env, FooterDataView footerData,
                                     ExtensionContext ctx) {
        return new FooterComponent(env, footerData, ctx);
    }

    /** pi L46：{@code n < 1000 ? `${n}` : `${(n/1000).toFixed(1)}k`}。 */
    static String formatTokens(double n) {
        if (n < 1000) {
            return plainNumber(n);
        }
        return String.format(Locale.ROOT, "%.1fk", n / 1000);
    }

    /** JS 的 {@code `${n}`}：整数值不带小数点。 */
    private static String plainNumber(double n) {
        if (n == Math.rint(n) && !Double.isInfinite(n)) {
            return String.valueOf((long) n);
        }
        return String.valueOf(n);
    }

    /** pi L35-42：分支上 assistant 消息的用量汇总 {@code [input, output, costTotal]}。 */
    static double[] sumUsage(List<Entry> branch) {
        var input = 0d;
        var output = 0d;
        var cost = 0d;
        for (var entry : branch) {
            if (entry instanceof Entry.Message m
                    && m.message() instanceof Message.AssistantMessage assistant
                    && assistant.usage() != null) {
                var usage = assistant.usage();
                input += usage.input();
                output += usage.output();
                cost += usage.cost() == null ? 0d : usage.cost().total();
            }
        }
        return new double[] {input, output, cost};
    }

    /** pi L48-53：整行拼装（左dim段＋空格补齐＋右dim段，再截到宽度）。 */
    static String renderLine(ExtensionUiHostEnv env, String branch, String modelId,
                             double[] usage, int width) {
        var left = env.colorize().apply("dim",
            "↑" + formatTokens(usage[0]) + " ↓" + formatTokens(usage[1])
                + " $" + String.format(Locale.ROOT, "%.3f", usage[2]));
        var branchStr = branch == null || branch.isEmpty() ? "" : " (" + branch + ")";
        var right = env.colorize().apply("dim", modelId + branchStr);
        var pad = Math.max(1, width - ExampleText.width(left) - ExampleText.width(right));
        return ExampleText.truncate(left + " ".repeat(pad) + right, width);
    }

    /** pi L27-55：footer 组件（每次 render 现读 ctx 与 footerData）。 */
    private static final class FooterComponent implements ExtensionComponent {

        private final ExtensionUiHostEnv env;
        private final FooterDataView footerData;
        private final ExtensionContext ctx;

        FooterComponent(ExtensionUiHostEnv env, FooterDataView footerData,
                        ExtensionContext ctx) {
            this.env = env;
            this.footerData = footerData;
            this.ctx = ctx;
        }

        @Override
        public List<String> render(int width) {
            var usage = sumUsage(ctx.sessionManager().getBranch());
            var modelId = ctx.getModel().map(m -> m.modelId()).orElse(NO_MODEL);
            return List.of(renderLine(env, footerData.gitBranch().get(), modelId, usage,
                width));
        }

        /** pi 的 {@code invalidate(){}}：无缓存，空实现。 */
        @Override
        public void handleInput(com.pijava.coding.agent.core.KeybindingsManager.KeyStroke key) {
            // footer 不收键（pi 组件无 handleInput 覆写 ⇒ 默认忽略）
        }
    }
}
