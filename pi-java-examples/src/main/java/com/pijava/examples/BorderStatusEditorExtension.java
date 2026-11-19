package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import com.pijava.coding.agent.core.KeybindingsManager.KeyStroke;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionExec;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/border-status-editor.ts:1-150} 移植（docs/27 W5）——
 * 自定义编辑器把状态直接画进上下边框：模型·思考级别在左下，上下文占用·cwd·git 分支
 * 在右下，agent 工作时左上转盲文 spinner。
 *
 * <p>逐字对位：{@link #fitBorder} 的两段截断与最小间隔、{@link #formatContext} 的
 * {@code "ctx ?"} 回落、{@code formatCwd} 的 {@code ~} 缩短、{@code agent_start}／
 * {@code agent_settled} 的 spinner 起停（80ms，D246 先例的可注入 {@link TickDriver}）、
 * {@code session_start} 的 {@code setWorkingVisible(false)}＋空 footer＋
 * {@code setEditorComponent}＋{@code git branch --show-current} 现读。</p>
 *
 * <p>窄差：{@code CustomEditor} 的 {@code paddingX: 0} 选项无对位（文本级基座无内边距），
 * 见 {@link ExampleEditor}。</p>
 */
public final class BorderStatusEditorExtension implements PiExtension {

    /** pi L76：spinner 帧（逐字）。 */
    static final List<String> SPINNER_FRAMES =
        List.of("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏");

    /** pi L91：帧间隔毫秒（逐字）。 */
    static final int SPINNER_INTERVAL_MS = 80;

    /** pi L54：上下文不可得时的占位（逐字）。 */
    static final String UNKNOWN_CONTEXT = "ctx ?";

    /** pi L131：无模型时的占位（逐字）。 */
    static final String NO_MODEL = "no model";

    /** 注入式 tick 驱动（D246 先例）。 */
    interface TickDriver {
        void start(Runnable tick);

        void stop();
    }

    private boolean working;
    private int spinnerIndex;
    private String branch;
    private Runnable requestRender = () -> { };
    private TickDriver driver = new TickDriver() {
        public void start(Runnable tick) { }

        public void stop() { }
    };

    @Override
    public String name() {
        return "border-status-editor";
    }

    /** 驱动注入（测试手动推帧）。 */
    void driver(TickDriver driver) {
        this.driver = driver;
    }

    /** 是否在工作（测试探针）。 */
    boolean working() {
        return working;
    }

    /** 当前 spinner 帧号（测试探针）。 */
    int spinnerIndex() {
        return spinnerIndex;
    }

    /** 现读到的 git 分支（测试探针）。 */
    String branch() {
        return branch;
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.on(ExtensionEventSpec.AGENT_START, (event, c) -> {
            working = true;
            stopSpinner();
            startSpinner();
            requestRender.run();
            return null;
        });
        ctx.on(ExtensionEventSpec.AGENT_SETTLED, (event, c) -> {
            working = false;
            stopSpinner();
            requestRender.run();
            return null;
        });
        ctx.on(ExtensionEventSpec.SESSION_SHUTDOWN, (event, c) -> {
            stopSpinner();
            requestRender = () -> { };
            return null;
        });
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            onSessionStart(c);
            return null;
        });
    }

    /** pi L106-149：会话开始时装槽位。 */
    void onSessionStart(ExtensionContext ctx) {
        ctx.ui().setWorkingVisible(false);
        ctx.ui().setFooter((env, footerData) -> new EmptyFooter());
        refreshBranch(ctx);
        ctx.ui().setEditorComponent((env, keybindings) -> {
            requestRender = env.requestRender();
            return new BorderStatusEditor(env, ctx, this);
        });
    }

    /** pi L112-118：{@code git branch --show-current}，失败即无分支。 */
    void refreshBranch(ExtensionContext ctx) {
        try {
            var result = ctx.exec("git", List.of("branch", "--show-current"),
                new ExtensionExec.ExecOptions(null, null, ctx.cwd()));
            var stdout = result == null || result.stdout() == null ? "" : result.stdout().trim();
            branch = stdout.isEmpty() ? null : stdout;
        } catch (RuntimeException e) {
            branch = null;
        }
        requestRender.run();
    }

    private void startSpinner() {
        driver.start(() -> {
            spinnerIndex = (spinnerIndex + 1) % SPINNER_FRAMES.size();
            requestRender.run();
        });
    }

    private void stopSpinner() {
        driver.stop();
    }

    /** pi L10-40：{@code fitBorder}（首末边框的行内布局与截断）。 */
    static String fitBorder(String left, String right, int width, UnaryOperator<String> border) {
        return fitBorder(left, right, width, border, border);
    }

    /** {@link #fitBorder(String, String, int, UnaryOperator)} 的带填充形。 */
    static String fitBorder(String left, String right, int width, UnaryOperator<String> border,
                            UnaryOperator<String> fill) {
        if (width <= 0) {
            return "";
        }
        if (width == 1) {
            return border.apply(ExampleEditor.BORDER_GLYPH);
        }
        var leftText = left == null ? "" : left;
        var rightText = right == null ? "" : right;
        var fixedWidth = 2;
        var minimumGap = 3;
        while (fixedWidth + ExampleText.width(leftText) + ExampleText.width(rightText)
                + minimumGap > width && ExampleText.width(rightText) > 0) {
            rightText = ExampleText.truncate(rightText,
                Math.max(0, ExampleText.width(rightText) - 1));
        }
        while (fixedWidth + ExampleText.width(leftText) + ExampleText.width(rightText)
                + minimumGap > width && ExampleText.width(leftText) > 0) {
            leftText = ExampleText.truncate(leftText,
                Math.max(0, ExampleText.width(leftText) - 1));
        }
        var gapWidth = Math.max(0,
            width - fixedWidth - ExampleText.width(leftText) - ExampleText.width(rightText));
        var glyph = ExampleEditor.BORDER_GLYPH;
        return border.apply(glyph) + leftText + fill.apply(glyph.repeat(gapWidth))
            + rightText + border.apply(glyph);
    }

    /** pi L42-48：{@code $HOME} 前缀缩成 {@code ~}。 */
    static String formatCwd(String cwd, String home) {
        if (cwd == null) {
            return "";
        }
        if (home != null && !home.isEmpty() && cwd.startsWith(home)) {
            return "~" + cwd.substring(home.length());
        }
        return cwd;
    }

    /** pi L50-57：{@code "ctx <pct>%/<k>k"}；任一项不可得 ⇒ {@code "ctx ?"}。 */
    static String formatContext(ExtensionContext ctx) {
        var usage = ctx.getContextUsage().orElse(null);
        var model = ctx.getModel().orElse(null);
        // pi 的 `usage?.contextWindow ?? ctx.model?.contextWindow`（显式装箱：三元的
        // int/Integer 混合分支会被编译器拆箱，null 分支就不可达了）
        Integer contextWindow = null;
        if (usage != null) {
            contextWindow = usage.contextWindow();
        } else if (model != null) {
            contextWindow = model.contextWindow();
        }
        if (contextWindow == null || contextWindow == 0 || usage == null
                || usage.percent() == null) {
            return UNKNOWN_CONTEXT;
        }
        return "ctx " + Math.round(usage.percent()) + "%/"
            + String.format(Locale.ROOT, "%.0f", contextWindow / 1000.0) + "k";
    }

    /** pi L59-61：{@code level === "off" ? "off" : level}。 */
    static String formatThinking(String level) {
        return "off".equals(level) ? "off" : level;
    }

    /** pi L71-76：模型与思考级别段（左下）。 */
    String bottomLeft(ExtensionContext ctx, ExtensionUiHostEnv env) {
        var model = ctx.getModel();
        var label = model.map(m -> m.provider() + "/" + m.modelId()).orElse(NO_MODEL);
        var thinking = formatThinking(ctx.getThinkingLevel().label());
        return env.colorize().apply("muted", " " + label + " · " + thinking + " ");
    }

    /** pi L136-139：上下文、cwd 与分支段（右下）。 */
    String bottomRight(ExtensionContext ctx, ExtensionUiHostEnv env) {
        var branchSuffix = branch == null ? "" : " (" + branch + ")";
        return env.colorize().apply("muted",
            " " + formatContext(ctx) + " · " + formatCwd(ctx.cwd(), System.getenv("HOME"))
                + branchSuffix + " ");
    }

    /** pi L133：工作时左上角转 spinner（首尾各一空格）。 */
    String topLeft(ExtensionUiHostEnv env) {
        return working
            ? env.colorize().apply("accent", " " + SPINNER_FRAMES.get(spinnerIndex) + " ")
            : "";
    }

    /** pi L63-69：把内置 footer 换成空组件。 */
    static final class EmptyFooter implements ExtensionComponent {

        @Override
        public List<String> render(int width) {
            return List.of();
        }

        @Override
        public void handleInput(KeyStroke key) {
            // 空 footer 不收键
        }
    }

    /** pi L120-146：{@code class BorderStatusEditor extends CustomEditor}。 */
    static final class BorderStatusEditor extends ExampleEditor {

        private final ExtensionUiHostEnv env;
        private final ExtensionContext ctx;
        private final BorderStatusEditorExtension extension;

        BorderStatusEditor(ExtensionUiHostEnv env, ExtensionContext ctx,
                           BorderStatusEditorExtension extension) {
            super(ExampleEditor.colorizeOf(env));
            this.env = env;
            this.ctx = ctx;
            this.extension = extension;
        }

        /** pi L126-145：首末行改成带状态段的边框。 */
        @Override
        public List<String> render(int width) {
            var lines = new ArrayList<>(super.render(width));
            if (lines.size() < 2) {
                return lines;
            }
            UnaryOperator<String> borderColor = this::borderColor;
            lines.set(0, fitBorder(extension.topLeft(env), "", width, borderColor));
            lines.set(lines.size() - 1, fitBorder(extension.bottomLeft(ctx, env),
                extension.bottomRight(ctx, env), width, borderColor));
            return lines;
        }
    }
}
