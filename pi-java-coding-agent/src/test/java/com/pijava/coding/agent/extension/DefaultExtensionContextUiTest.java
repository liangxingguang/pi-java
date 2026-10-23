package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.pijava.agent.skill.SkillManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.UI_PROMPT_END;
import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.UI_PROMPT_START;

/**
 * 批 17（T1/T2，docs/28 §7-3）：{@link DefaultExtensionContext} 的 UI 晚绑定——
 * pi runner.ts:876-884「call time 解析」逐字：create 后换 UI（RPC 序对位：先 noop
 * 后注入）{@code ctx.ui()} 见新值；{@code hasUi()}/{@code mode()} 现读；
 * {@link ExtensionUI#NOOP} 恒等不包装。
 */
class DefaultExtensionContextUiTest {

    @Test
    void uiResolvesAtCallTime() {
        var slot = new AtomicReference<ExtensionUI>(ExtensionUI.NOOP);
        var mode = new AtomicReference<>("print");
        var ctx = new DefaultExtensionContext(null, new SkillManager(),
            slot::get, mode::get, new ExtensionEventBus());

        // 初始 noop（pi hasUI()＝恒等比较，runner.ts:622）。
        assertThat(ctx.ui()).isSameAs(ExtensionUI.NOOP);
        assertThat(ctx.hasUi()).isFalse();
        assertThat(ctx.mode()).isEqualTo("print");

        // 后注入（RPC 装配序对位）⇒ 现取见新值；包装器非 NOOP 且按 delegate 缓存。
        var injected = new ExtensionUI() {
            @Override
            public String select(String title, List<String> options,
                                 ExtensionUiDialogOptions opts) {
                return "chosen";
            }
        };
        slot.set(injected);
        mode.set("rpc");
        assertThat(ctx.hasUi()).isTrue();
        assertThat(ctx.mode()).isEqualTo("rpc");
        var first = ctx.ui();
        assertThat(first).isNotSameAs(ExtensionUI.NOOP);
        assertThat(first.select("t", List.of("a"), null)).isEqualTo("chosen");
        assertThat(ctx.ui()).isSameAs(first);   // 同 delegate 复用同一包装器（depth 随它）

        // 回退 noop ⇒ 恢复恒等（pi setUIContext(undefined) 臂）。
        slot.set(ExtensionUI.NOOP);
        assertThat(ctx.ui()).isSameAs(ExtensionUI.NOOP);
        assertThat(ctx.hasUi()).isFalse();
    }

    @Test
    void wrappedUiFiresUiPromptEvents() throws InterruptedException {
        var bus = new ExtensionEventBus();
        var slot = new AtomicReference<ExtensionUI>(new ExtensionUI() {
            @Override
            public String input(String title, String placeholder, ExtensionUiDialogOptions opts) {
                return "typed";
            }
        });
        var ctx = new DefaultExtensionContext(null, new SkillManager(),
            slot::get, () -> "tui", bus);
        ctx.bindUiPromptFirer(new SessionEventFirer(bus, ctx));

        var seen = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(2);
        bus.on("ext", UI_PROMPT_START, (e, c) -> {
            seen.add("start:" + e.kind() + ":" + e.title());
            latch.countDown();
            return null;
        });
        bus.on("ext", UI_PROMPT_END, (e, c) -> {
            seen.add("end:" + e.kind() + ":" + e.title());
            latch.countDown();
            return null;
        });

        assertThat(ctx.ui().input("name?", null, null)).isEqualTo("typed");
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).containsExactly("start:input:name?", "end:input:name?");
    }

    @Test
    void perExtensionDelegatesInheritUiModeAndHasUi() {
        var slot = new AtomicReference<ExtensionUI>(new ExtensionUI() { });
        var ctx = new DefaultExtensionContext(null, new SkillManager(),
            slot::get, () -> "tui", new ExtensionEventBus());
        var ext = ctx.forExtension("ext-a");
        assertThat(ext.mode()).isEqualTo("tui");
        assertThat(ext.hasUi()).isTrue();
        assertThat(ext.ui()).isSameAs(ctx.ui());
    }

    @Test
    void legacyConstructorKeepsBakedUiAndPrintMode() {
        // 旧四参形状（烘值）：ui() 现读仍是该值；mode 恒 "print"。
        var baked = new ExtensionUI() { };
        var ctx = new DefaultExtensionContext(null, new SkillManager(),
            baked, new ExtensionEventBus());
        assertThat(ctx.ui()).isNotSameAs(ExtensionUI.NOOP);
        assertThat(ctx.hasUi()).isTrue();
        assertThat(ctx.mode()).isEqualTo("print");
        assertThat(new DefaultExtensionContext(null, new SkillManager()).ui())
            .isSameAs(ExtensionUI.NOOP);
    }
}
