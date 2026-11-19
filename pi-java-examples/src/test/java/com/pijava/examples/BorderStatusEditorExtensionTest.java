package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import com.pijava.ai.model.ModelId;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.ai.thinking.ThinkingLevel;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.extension.ExtensionContextUsage;
import com.pijava.coding.agent.extension.ExtensionExec;
import com.pijava.coding.agent.extension.ExtensionModelInfo;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

import org.junit.jupiter.api.Test;

/** docs/27 W5：border-status-editor 验收——fitBorder、三段格式化、槽位接线与 spinner。 */
class BorderStatusEditorExtensionTest {

    private static final ModelId<?> CLAUDE = ModelId.of("anthropic", "claude");
    private static final UnaryOperator<String> PLAIN = text -> text;

    private static ExtensionUiHostEnv env(int width, AtomicInteger renders) {
        return new ExtensionUiHostEnv(() -> width, () -> 24, renders::incrementAndGet,
            (slot, text) -> text);
    }

    @Test
    void fitBorderEdgesAndFilling() {
        assertThat(BorderStatusEditorExtension.fitBorder("L", "R", 0, PLAIN)).isEmpty();
        assertThat(BorderStatusEditorExtension.fitBorder("L", "R", 1, PLAIN)).isEqualTo("─");

        var line = BorderStatusEditorExtension.fitBorder("L", "R", 20, PLAIN);
        assertThat(line).startsWith("─L").endsWith("R─");
        assertThat(line).hasSize(20);

        var narrow = BorderStatusEditorExtension.fitBorder(
            "yyyyyyyyyyyyyy", "rrrrrrrrrrrrrr", 10, PLAIN);
        assertThat(narrow).hasSize(10);
        // 先削右段到空，再把左段削到 5 列（2 边框＋3 间隙）
        assertThat(narrow).isEqualTo("─yyyyy────");
    }

    @Test
    void fitBorderUsesDistinctFillColorizer() {
        var line = BorderStatusEditorExtension.fitBorder("L", "R", 6, PLAIN,
            text -> "<" + text + ">");
        // 6 列先削掉右段（2+1+1+3 > 6），间隙 3 列走 fill
        assertThat(line).isEqualTo("─L<───>─");
    }

    @Test
    void formatCwdShortensHome() {
        assertThat(BorderStatusEditorExtension.formatCwd("/home/u/proj", "/home/u"))
            .isEqualTo("~/proj");
        assertThat(BorderStatusEditorExtension.formatCwd("/work", null)).isEqualTo("/work");
        assertThat(BorderStatusEditorExtension.formatCwd("/work", "")).isEqualTo("/work");
        assertThat(BorderStatusEditorExtension.formatCwd(null, "/home/u")).isEmpty();
    }

    @Test
    void formatContextThreeArms() {
        var w = new W3Context();
        assertThat(BorderStatusEditorExtension.formatContext(w.ctx))
            .isEqualTo(BorderStatusEditorExtension.UNKNOWN_CONTEXT);

        w.contextUsage = Optional.of(new ExtensionContextUsage(null, 200000, null));
        assertThat(BorderStatusEditorExtension.formatContext(w.ctx))
            .isEqualTo(BorderStatusEditorExtension.UNKNOWN_CONTEXT);

        w.contextUsage = Optional.of(new ExtensionContextUsage(85000, 200000, 42.5));
        assertThat(BorderStatusEditorExtension.formatContext(w.ctx)).isEqualTo("ctx 43%/200k");
    }

    @Test
    void formatThinkingPassesLevelThrough() {
        assertThat(BorderStatusEditorExtension.formatThinking("off")).isEqualTo("off");
        assertThat(BorderStatusEditorExtension.formatThinking("high")).isEqualTo("high");
    }

    @Test
    void sessionStartWiresSlotsAndReadsBranch() {
        var w = new W3Context();
        w.execResultFn = result -> new ExtensionExec.ExecResult("main\n", "", 0, false, false);
        var ext = new BorderStatusEditorExtension();
        ext.register(w.ctx);

        w.fire(ExtensionEventSpec.SESSION_START, null);

        assertThat(w.ui.workingVisibles()).containsExactly(false);
        assertThat(w.ui.footers()).hasSize(1);
        assertThat(w.ui.editorFactories()).hasSize(1);
        assertThat(w.execLog).containsExactly("git branch --show-current");
        assertThat(ext.branch()).isEqualTo("main");
    }

    @Test
    void emptyFooterRendersNothing() {
        assertThat(new BorderStatusEditorExtension.EmptyFooter().render(10)).isEmpty();
    }

    @Test
    void spinnerTicksWhileWorking() {
        var ticks = new ArrayList<Runnable>();
        var ext = new BorderStatusEditorExtension();
        ext.driver(new BorderStatusEditorExtension.TickDriver() {
            public void start(Runnable tick) {
                ticks.add(tick);
            }

            public void stop() {
                ticks.clear();
            }
        });
        var w = new W3Context();
        ext.register(w.ctx);

        w.fire(ExtensionEventSpec.AGENT_START, null);
        assertThat(ext.working()).isTrue();
        assertThat(ticks).hasSize(1);
        ticks.get(0).run();
        assertThat(ext.spinnerIndex()).isEqualTo(1);

        w.fire(ExtensionEventSpec.AGENT_SETTLED, null);
        assertThat(ext.working()).isFalse();
        assertThat(ticks).isEmpty();

        w.fire(ExtensionEventSpec.AGENT_START, null);
        w.fire(ExtensionEventSpec.SESSION_SHUTDOWN, null);
        assertThat(ticks).isEmpty();
    }

    @Test
    void editorRendersStatusSegmentsIntoBorders() {
        var w = new W3Context();
        w.model = Optional.of(new ExtensionModelInfo(CLAUDE, "Claude", 200000, 8192));
        w.thinkingLevel = ModelThinkingLevel.of(new ThinkingLevel.High());
        w.contextUsage = Optional.of(new ExtensionContextUsage(85000, 200000, 42.5));
        w.cwd = "/work";
        w.execResultFn = result -> new ExtensionExec.ExecResult("main\n", "", 0, false, false);

        var ext = new BorderStatusEditorExtension();
        ext.register(w.ctx);
        w.fire(ExtensionEventSpec.SESSION_START, null);
        w.fire(ExtensionEventSpec.AGENT_START, null);

        var editor = w.ui.editorFactories().get(0)
            .create(env(100, new AtomicInteger()), new KeybindingsManager());
        var lines = editor.render(100);

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains(" ⠋ ");
        assertThat(lines.get(2)).contains("anthropic/claude · high")
            .contains("ctx 43%/200k")
            .contains("/work")
            .contains("(main)");
        assertThat(ExampleText.width(lines.get(0))).isEqualTo(100);
        assertThat(ExampleText.width(lines.get(2))).isEqualTo(100);

        // 窄宽度只截断、不抛
        assertThat(editor.render(20)).hasSize(3);
    }

    @Test
    void editorFallsBackWhenModelAndContextMissing() {
        var w = new W3Context();
        var ext = new BorderStatusEditorExtension();
        ext.register(w.ctx);
        w.fire(ExtensionEventSpec.SESSION_START, null);

        var editor = w.ui.editorFactories().get(0)
            .create(env(80, new AtomicInteger()), new KeybindingsManager());
        var lines = editor.render(80);

        assertThat(lines.get(0)).doesNotContain("⠋");
        assertThat(lines.get(2)).contains(BorderStatusEditorExtension.NO_MODEL)
            .contains(BorderStatusEditorExtension.UNKNOWN_CONTEXT);
        assertThat(ext.branch()).isNull();
    }
}
