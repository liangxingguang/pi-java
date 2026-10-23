package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.UI_PROMPT_END;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.UI_PROMPT_START;

/**
 * 批 17（T2，docs/28 §7-2）：ui_prompt 包装装饰器——pi {@code withUIPrompt}
 * （runner.ts:598-622）逐字对拍：五方法各发 start/end 对；嵌套只发最外层一对
 * （end 取最外层 kind/title）；委托同步抛 ⇒ 深度复位＋end 仍发＋异常上抛；
 * 异步发射经 latch 收。
 */
class UiPromptEventExtensionUITest {

    private ExtensionEventBus bus;
    private SessionEventFirer firer;
    private List<String> events;
    /** 发射异步 ⇒ latch 必须先布防再触发（countDown 落在处理器内）。 */
    private volatile CountDownLatch latch = new CountDownLatch(0);

    /** 记录五件调用的假 delegate。 */
    private static class FakeUI implements ExtensionUI {
        final List<String> calls = new CopyOnWriteArrayList<>();
        /** custom 内嵌套调用的钩（测试注入——经包装器回调 select）。 */
        volatile java.util.function.Supplier<String> nestedCall;

        @Override
        public String select(String title, List<String> options, ExtensionUiDialogOptions opts) {
            calls.add("select:" + title);
            return "picked";
        }

        @Override
        public boolean confirm(String title, String message, ExtensionUiDialogOptions opts) {
            calls.add("confirm:" + title);
            return true;
        }

        @Override
        public String input(String title, String placeholder, ExtensionUiDialogOptions opts) {
            calls.add("input:" + title);
            return "typed";
        }

        @Override
        public String editor(String title, String prefill) {
            calls.add("editor:" + title);
            return "edited";
        }

        @Override
        public <T> T custom(ExtensionComponentFactory<T> factory, ExtensionCustomOptions options) {
            calls.add("custom");
            var nested = nestedCall;
            if (nested != null) {
                nested.get();   // 嵌套：custom 里再 select（pi 示例逐字语义）
            }
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        bus = new ExtensionEventBus();
        firer = new SessionEventFirer(bus, null);
        events = new CopyOnWriteArrayList<>();
        bus.on("test", UI_PROMPT_START, (e, c) -> {
            events.add("start:" + e.kind() + (e.title() != null ? ":" + e.title() : ""));
            latch.countDown();
            return null;
        });
        bus.on("test", UI_PROMPT_END, (e, c) -> {
            events.add("end:" + e.kind() + (e.title() != null ? ":" + e.title() : ""));
            latch.countDown();
            return null;
        });
    }

    /** 布防：预期 n 个异步事件。 */
    private void expectEvents(int count) {
        latch = new CountDownLatch(count);
    }

    private void awaitEvents() throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS))
            .as("异步发射的 ui_prompt 事件（queueMicrotask ⇒ 虚拟线程）").isTrue();
    }

    @Test
    void eachBlockingMethodEmitsStartEndPair() throws InterruptedException {
        var delegate = new FakeUI();
        var ui = new UiPromptEventExtensionUI(delegate, () -> firer);

        expectEvents(2);
        assertThat(ui.select("pick", List.of("a", "b"), null)).isEqualTo("picked");
        awaitEvents();
        assertThat(events).containsExactly("start:select:pick", "end:select:pick");

        expectEvents(2);
        assertThat(ui.confirm("sure?", "do it", null)).isTrue();
        awaitEvents();
        assertThat(events).containsExactly("start:select:pick", "end:select:pick",
            "start:confirm:sure?", "end:confirm:sure?");

        expectEvents(2);
        assertThat(ui.input("name", "hint", null)).isEqualTo("typed");
        awaitEvents();
        assertThat(events).contains("start:input:name", "end:input:name");

        expectEvents(2);
        assertThat(ui.editor("compose", "pre")).isEqualTo("edited");
        awaitEvents();
        assertThat(events).contains("start:editor:compose", "end:editor:compose");

        expectEvents(2);
        ui.custom((env, keys, done) -> null, null);
        awaitEvents();
        // custom 无 title（pi wrapUIPromptContext 的 undefined 逐字——键省略 ⇒ null 列）。
        assertThat(events).contains("start:custom", "end:custom");
    }

    @Test
    void nestedPromptsEmitOnlyOutermostPair() throws InterruptedException {
        var delegate = new FakeUI();
        var ui = new UiPromptEventExtensionUI(delegate, () -> firer);
        delegate.nestedCall = () -> ui.select("inner", List.of("x"), null);

        expectEvents(2);
        ui.custom((env, keys, done) -> null, null);
        awaitEvents();

        // pi :598-613：嵌套只有最外层发 start；end 的 kind/title 取最外层记录值。
        assertThat(events).containsExactly("start:custom", "end:custom");
        assertThat(delegate.calls).containsExactly("custom", "select:inner");
    }

    @Test
    void synchronousThrowStillFiresEndAndResetsDepth() throws InterruptedException {
        var delegate = new FakeUI() {
            @Override
            public String select(String title, List<String> options,
                                 ExtensionUiDialogOptions opts) {
                super.select(title, options, opts);
                throw new IllegalStateException("boom");
            }
        };
        var ui = new UiPromptEventExtensionUI(delegate, () -> firer);

        expectEvents(2);
        assertThatThrownBy(() -> ui.select("explode", List.of("a"), null))
            .isInstanceOf(IllegalStateException.class).hasMessage("boom");
        awaitEvents();
        // pi try/catch：finish 先跑（end 仍发）、异常重抛。
        assertThat(events).containsExactly("start:select:explode", "end:select:explode");

        // 深度恒复位：下一次调用是全新的外层。
        expectEvents(2);
        assertThat(ui.confirm("again", "m", null)).isTrue();
        awaitEvents();
        assertThat(events).containsExactly("start:select:explode", "end:select:explode",
            "start:confirm:again", "end:confirm:again");
    }

    @Test
    void nonWrappedMembersPassThroughWithoutEvents() {
        var delegate = new FakeUI();
        var ui = new UiPromptEventExtensionUI(delegate, () -> firer);
        ui.notify("m", NotifyType.INFO);
        ui.setStatus("k", "v");
        ui.setTitle("t");
        assertThat(events).isEmpty();
    }

    /** 批 17 评审回补（Minor 2）：包装器透明性——生命周期方法直委托（pi 的
     *  {@code ...ui} 展开对位——不包的方法全部透传，含 T7 的 onSessionInvalidated）。 */
    @Test
    void onSessionInvalidatedDelegatesThroughWrapper() {
        var invalidated = new java.util.concurrent.atomic.AtomicInteger();
        var delegate = new ExtensionUI() {
            @Override
            public void onSessionInvalidated() {
                invalidated.incrementAndGet();
            }
        };
        var ui = new UiPromptEventExtensionUI(delegate, () -> firer);
        ui.onSessionInvalidated();
        assertThat(invalidated.get()).isEqualTo(1);
    }

    @Test
    void nullFirerSkipsEmission() {
        // firer 未回填（loadExtensions 早期）⇒ 静默不发射（供应器现读）。
        var ui = new UiPromptEventExtensionUI(new FakeUI(), () -> null);
        assertThat(ui.select("t", List.of("a"), null)).isEqualTo("picked");
        assertThat(events).isEmpty();
    }
}
