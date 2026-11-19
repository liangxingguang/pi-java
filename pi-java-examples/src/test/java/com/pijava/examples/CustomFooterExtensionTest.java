package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.Message;
import com.pijava.ai.message.StopReason;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.ExtensionModelInfo;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.FooterDataView;

import org.junit.jupiter.api.Test;

/** docs/27 W5：custom-footer 验收——开关两臂、用量汇总、深浅色段与宽度截断。 */
class CustomFooterExtensionTest {

    private static final ModelId<?> CLAUDE = ModelId.of("anthropic", "claude");

    /** 测试环境：色槽产 markup（与 TUI 臂同形），重绘计数。 */
    private static ExtensionUiHostEnv env(AtomicInteger renders) {
        return new ExtensionUiHostEnv(() -> 80, () -> 24, renders::incrementAndGet,
            (slot, text) -> "[" + slot + "]" + text + "[/]");
    }

    private static Entry assistantEntry(double input, double output, double cost) {
        var usage = new Usage(input, output, 0, 0, null, null, input + output,
            new Usage.Cost(cost, 0, 0, 0, cost));
        var message = new Message.AssistantMessage(List.of(), StopReason.STOP, null,
            "anthropic", "anthropic", "claude", null, null, null, null, usage,
            Instant.EPOCH, null, null, null, null);
        return new Entry.Message("e1", 1, null, Instant.EPOCH, message, null);
    }

    @Test
    void toggleRegistersAndClearsFooter() {
        var w = new W3Context();
        var ext = new CustomFooterExtension();
        ext.register(w.ctx);

        w.command("footer", "");
        assertThat(ext.enabled()).isTrue();
        assertThat(w.ui.footers()).hasSize(1);
        assertThat(w.ui.footers().get(0)).isNotNull();
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Custom footer enabled");

        w.command("footer", "");
        assertThat(ext.enabled()).isFalse();
        assertThat(w.ui.footers()).hasSize(2);
        assertThat(w.ui.footers().get(1)).isNull();
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .last().isEqualTo("Default footer restored");
    }

    @Test
    void formatTokensMatchesJsTemplate() {
        assertThat(CustomFooterExtension.formatTokens(0)).isEqualTo("0");
        assertThat(CustomFooterExtension.formatTokens(999)).isEqualTo("999");
        assertThat(CustomFooterExtension.formatTokens(1000)).isEqualTo("1.0k");
        assertThat(CustomFooterExtension.formatTokens(1234)).isEqualTo("1.2k");
        assertThat(CustomFooterExtension.formatTokens(12345)).isEqualTo("12.3k");
    }

    @Test
    void sumUsageAddsAssistantMessagesOnly() {
        var branch = List.of(
            assistantEntry(1000, 200, 0.01),
            new Entry.ModelChange("e2", 2, "e1", Instant.EPOCH, "anthropic", "claude"),
            assistantEntry(200, 300, 0.005));
        var sum = CustomFooterExtension.sumUsage(branch);
        assertThat(sum[0]).isEqualTo(1200d);
        assertThat(sum[1]).isEqualTo(500d);
        assertThat(sum[2]).isEqualTo(0.015d);
    }

    @Test
    void footerComponentRendersUsageAndBranch() {
        var renders = new AtomicInteger();
        var branchRef = new AtomicReference<String>("main");
        var footerData = new FooterDataView(branchRef::get, () -> Map.of("k", "v"));
        var w = new W3Context();
        w.branch = List.of(assistantEntry(1200, 500, 0.015));
        w.model = Optional.of(new ExtensionModelInfo(CLAUDE, "Claude", 200000, 8192));

        var component = CustomFooterExtension.footer(env(renders), footerData, w.ctx);
        var lines = component.render(40);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("[dim]↑1.2k ↓500 $0.015[/]");
        assertThat(lines.get(0)).contains("[dim]claude (main)[/]");
        assertThat(ExampleText.width(lines.get(0))).isLessThanOrEqualTo(40);

        // 现读：分支变化后同一组件下次渲染即反映
        branchRef.set("feature/x");
        assertThat(component.render(40).get(0)).contains("claude (feature/x)");
    }

    @Test
    void footerFallsBackToNoModelAndNoBranch() {
        var renders = new AtomicInteger();
        var footerData = new FooterDataView(() -> null, () -> Map.of());
        var w = new W3Context();
        w.branch = List.of();
        w.model = Optional.empty();

        var component = CustomFooterExtension.footer(env(renders), footerData, w.ctx);
        var line = component.render(30).get(0);

        assertThat(line).contains("↑0 ↓0 $0.000");
        assertThat(line).contains("[dim]no-model[/]");
        assertThat(ExampleText.width(line)).isLessThanOrEqualTo(30);
    }
}
