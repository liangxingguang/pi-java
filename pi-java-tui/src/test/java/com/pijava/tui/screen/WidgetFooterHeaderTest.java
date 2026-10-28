package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionUiHostEnv;
import com.pijava.coding.agent.extension.ExtensionWidgetContent;
import com.pijava.coding.agent.extension.ExtensionWidgetOptions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 17（T6，docs/28 §7-10）：注入层——pi interactive-mode.ts:2398-2595 对拍：
 * setWidget 截断 10 行＋截断文案、同 key 两区清理（dispose 旧组件臂）、上区空
 * Spacer/下区空无 Spacer、placement 路由；setFooter/setHeader 换槽与回内置、
 * dispose 序；扩展状态段排序/sanitize/截断/空表不渲染。
 */
class WidgetFooterHeaderTest {

    private ChatScreen chat;

    /** 假宿主环境（colorize 直通便于断言）。 */
    private static final ExtensionUiHostEnv ENV = new ExtensionUiHostEnv(
        () -> 80, () -> 24, () -> { }, (slot, text) -> "[" + slot + "]" + text + "[/]");

    /** 计数组件（dispose 序断言面）。 */
    private static final class CountingComponent implements ExtensionComponent {
        final AtomicInteger disposeCalls = new AtomicInteger();
        private final String line;

        CountingComponent(String line) {
            this.line = line;
        }

        @Override
        public List<String> render(int width) {
            return List.of(line);
        }

        @Override
        public void handleInput(com.pijava.coding.agent.core.KeybindingsManager.KeyStroke key) { }

        @Override
        public void dispose() {
            disposeCalls.incrementAndGet();
        }
    }

    @BeforeEach
    void setUp() {
        chat = new ChatScreen();
    }

    private static ExtensionWidgetContent.Lines lines(String... lines) {
        return new ExtensionWidgetContent.Lines(List.of(lines));
    }

    // ── setWidget（pi :2398-2438）──

    @Test
    void widgetLinesTruncateAtTenWithMarker() {
        var many = new ArrayList<String>();
        for (int i = 1; i <= 13; i++) {
            many.add("line-" + i);
        }
        chat.setExtensionWidget("w", new ExtensionWidgetContent.Lines(many), null, ENV);
        var entry = (ExtensionInjectionLayer.WidgetEntry.Lines) chat.widgetsForProbe(true).get("w");
        // pi :2422-2426：slice(0, 10)＋截断行（文案逐字【模型不可见】）。
        assertThat(entry.lines()).hasSize(ExtensionInjectionLayer.MAX_WIDGET_LINES + 1);
        assertThat(entry.lines().get(9)).isEqualTo("line-10");
        assertThat(entry.lines().get(10)).isEqualTo("... (widget truncated)");
    }

    @Test
    void sameKeyClearedFromBothAreasBeforeReroute() {
        chat.setExtensionWidget("w", lines("above"), null, ENV);
        assertThat(chat.widgetsForProbe(true)).containsKey("w");

        // 同 key 改挂下区：pi 同 key 两边都先清（:2408-2409）。
        chat.setExtensionWidget("w", lines("below"),
            new ExtensionWidgetOptions(ExtensionWidgetOptions.BELOW_EDITOR), ENV);
        assertThat(chat.widgetsForProbe(true)).isEmpty();
        assertThat(chat.widgetsForProbe(false)).containsKey("w");
    }

    @Test
    void nullContentClearsSlot() {
        chat.setExtensionWidget("w", lines("x"), null, ENV);
        chat.setExtensionWidget("w", null, null, ENV);
        assertThat(chat.widgetsForProbe(true)).isEmpty();
        assertThat(chat.widgetsForProbe(false)).isEmpty();
    }

    @Test
    void replacingFactoryWidgetDisposesOldComponent() {
        var first = new CountingComponent("c1");
        chat.setExtensionWidget("w",
            new ExtensionWidgetContent.Factory(env -> first), null, ENV);
        chat.setExtensionWidget("w", lines("text instead"), null, ENV);
        assertThat(first.disposeCalls.get()).isEqualTo(1);
    }

    @Test
    void factoryArmInstantiatesViaHostEnv() {
        chat.setExtensionWidget("w",
            new ExtensionWidgetContent.Factory(
                env -> new CountingComponent(env.colorize().apply("accent", "hi"))),
            null, ENV);
        var entry = (ExtensionInjectionLayer.WidgetEntry.Comp) chat.widgetsForProbe(true).get("w");
        assertThat(entry.component().render(80)).containsExactly("[accent]hi[/]");
    }

    // ── 槽位 spacer 序（pi renderWidgetContainer :2491-2516）──

    @Test
    void spacerRulesDriveInlineHeight() {
        // 上区空＝Spacer(1)；下区空＝无 Spacer（pi spacerWhenEmpty/leadingSpacer 逐字）。
        assertThat(chat.extensionAreaLineCount()).isEqualTo(1);

        // 上区有内容：前导 Spacer(1)＋行数。
        chat.setExtensionWidget("a", lines("r1", "r2"), null, ENV);
        assertThat(chat.extensionAreaLineCount()).isEqualTo(3);

        // 下区有内容：无前导 Spacer。
        chat.setExtensionWidget("b", lines("r3"),
            new ExtensionWidgetOptions(ExtensionWidgetOptions.BELOW_EDITOR), ENV);
        assertThat(chat.extensionAreaLineCount()).isEqualTo(4);
    }

    // ── setFooter / setHeader（pi :2524-2595）──

    @Test
    void footerSwapsInAndBackToBuiltin() {
        var footer = new CountingComponent("my footer");
        chat.setExtensionFooter((env, data) -> footer, ENV);
        assertThat(chat.hasCustomFooter()).isTrue();
        assertThat(chat.statusBar()).isNotNull();    // 换槽渲染不崩

        chat.setExtensionFooter(null, ENV);          // 回内置（dispose 旧 custom 逐字）
        assertThat(chat.hasCustomFooter()).isFalse();
        assertThat(footer.disposeCalls.get()).isEqualTo(1);
    }

    @Test
    void footerFactoryReceivesDataViewWithExtensionStatuses() {
        chat.setExtensionStatus("ext-a", "on");
        var seen = new AtomicInteger(-1);
        chat.setExtensionFooter((env, data) -> {
            seen.set(data.extensionStatuses().get().size());
            return new CountingComponent("f");
        }, ENV);
        assertThat(seen.get()).isEqualTo(1);
        chat.setExtensionFooter(null, ENV);
    }

    @Test
    void headerSwapsAndClears() {
        var header = new CountingComponent("my header");
        chat.setExtensionHeader(env -> header, ENV);
        assertThat(chat.hasCustomHeader()).isTrue();

        chat.setExtensionHeader(null, ENV);
        assertThat(chat.hasCustomHeader()).isFalse();
        assertThat(header.disposeCalls.get()).isEqualTo(1);
    }

    // ── 扩展状态段（pi footer.ts:289-298＋:14-20）──

    @Test
    void statusesSortByKeySanitizeAndJoin() {
        chat.setExtensionStatus("beta", "two\nlines");
        chat.setExtensionStatus("alpha", "  padded\t\ttext  ");
        // key 字典序（alpha 先）；sanitize：[空白]→空格→折叠→trim（逐字）。
        assertThat(chat.extensionStatusLineText(80)).isEqualTo("padded text two lines");
    }

    @Test
    void emptyStatusesRenderNoLine() {
        assertThat(chat.extensionStatusLineText(80)).isNull();
        chat.setExtensionStatus("k", "v");
        assertThat(chat.extensionStatusLineText(80)).isEqualTo("v");
        chat.setExtensionStatus("k", null);          // null＝删键（pi :2300-2302）
        assertThat(chat.extensionStatusLineText(80)).isNull();
    }

    @Test
    void statusLineTruncatesToWidth() {
        chat.setExtensionStatus("a", "x".repeat(100));
        var line = chat.extensionStatusLineText(20);
        assertThat(line).hasSize(20).endsWith("...");  // pi truncateToWidth 默认省略号
    }

    @Test
    void sanitizeStatusTextVerbatim() {
        assertThat(ChatScreen.sanitizeStatusText("a\r\n\tb   c")).isEqualTo("a b c");
    }
}
