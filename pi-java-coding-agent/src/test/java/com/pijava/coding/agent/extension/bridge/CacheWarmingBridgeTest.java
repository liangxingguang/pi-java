package com.pijava.coding.agent.extension.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pijava.coding.agent.core.CacheWarming;
import com.pijava.coding.agent.extension.ExtensionError;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent.CacheWarmingDecisionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.CacheWarmingDecisionEventResult;

/**
 * 批 13（docs/24 §7）：pi {@code cache-warmer.test.ts:338-366} 的 runner 用例翻译
 * ＋异常臂——{@code emitCacheWarmingDecision}（runner.ts:1121-1142 逐字）：
 * last override wins；处理器抛错 ⇒ {@code emitError} 后继续、决策不丢；
 * 未安装/无订阅 ⇒ 事件自带 action（pi sdk.ts:338 的 {@code ?? event.action} 臂）。
 */
class CacheWarmingBridgeTest {

    private static CacheWarmingDecisionEvent event(CacheWarming.Action action) {
        return new CacheWarmingDecisionEvent(0.05, 0.5, 0.15, action);
    }

    /** pi :338-366：两扩展 warm→stop ⇒ "stop"（后注册的覆写胜）。 */
    @Test
    void usesTheLastExtensionOverride() {
        var bus = new ExtensionEventBus();
        bus.on("ext-a", ExtensionEventSpec.CACHE_WARMING_DECISION,
            (event, ctx) -> new CacheWarmingDecisionEventResult(CacheWarming.Action.WARM));
        bus.on("ext-b", ExtensionEventSpec.CACHE_WARMING_DECISION,
            (event, ctx) -> new CacheWarmingDecisionEventResult(CacheWarming.Action.STOP));
        var bridge = new CacheWarmingBridge();
        bridge.install(bus, null);

        assertThat(bridge.emitOrDefault(event(CacheWarming.Action.WARM)))
            .isEqualTo(CacheWarming.Action.STOP);
    }

    /** 处理器抛错 ⇒ emitError＋继续（决策由后续处理器照常推进）。 */
    @Test
    void handlerFailureEmitsErrorAndContinues() {
        var bus = new ExtensionEventBus();
        List<ExtensionError> errors = new ArrayList<>();
        bus.onError(errors::add);
        bus.on("ext-bad", ExtensionEventSpec.CACHE_WARMING_DECISION,
            (event, ctx) -> {
                throw new IllegalStateException("boom");
            });
        bus.on("ext-good", ExtensionEventSpec.CACHE_WARMING_DECISION,
            (event, ctx) -> new CacheWarmingDecisionEventResult(CacheWarming.Action.STOP));
        var bridge = new CacheWarmingBridge();
        bridge.install(bus, null);

        assertThat(bridge.emitOrDefault(event(CacheWarming.Action.WARM)))
            .isEqualTo(CacheWarming.Action.STOP);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).extensionName()).isEqualTo("ext-bad");
    }

    /** 全部处理器抛错 ⇒ 决策不丢（回落事件自带 action，pi :315-317 双保险的内层）。 */
    @Test
    void allHandlersFailingFallsBackToEventAction() {
        var bus = new ExtensionEventBus();
        List<ExtensionError> errors = new ArrayList<>();
        bus.onError(errors::add);
        bus.on("ext-bad", ExtensionEventSpec.CACHE_WARMING_DECISION,
            (event, ctx) -> {
                throw new IllegalStateException("boom");
            });
        var bridge = new CacheWarmingBridge();
        bridge.install(bus, null);

        assertThat(bridge.emitOrDefault(event(CacheWarming.Action.WARM)))
            .isEqualTo(CacheWarming.Action.WARM);
        assertThat(errors).hasSize(1);
    }

    /** null action 不发言（pi {@code result?.action !== undefined} 判据）＋未安装/无订阅臂。 */
    @Test
    void nullActionAndMissingBridgePassThrough() {
        var bus = new ExtensionEventBus();
        bus.on("ext-silent", ExtensionEventSpec.CACHE_WARMING_DECISION,
            (event, ctx) -> new CacheWarmingDecisionEventResult(null));
        var bridge = new CacheWarmingBridge();
        bridge.install(bus, null);
        assertThat(bridge.emitOrDefault(event(CacheWarming.Action.STOP)))
            .isEqualTo(CacheWarming.Action.STOP);

        // 无订阅 ⇒ 事件自带 action（pi sdk.ts:338）。
        var empty = new CacheWarmingBridge();
        empty.install(new ExtensionEventBus(), null);
        assertThat(empty.emitOrDefault(event(CacheWarming.Action.WARM)))
            .isEqualTo(CacheWarming.Action.WARM);

        // 未安装（--no-extensions 臂）⇒ 同。
        var bare = new CacheWarmingBridge();
        assertThat(bare.emitOrDefault(event(CacheWarming.Action.STOP)))
            .isEqualTo(CacheWarming.Action.STOP);
    }
}
