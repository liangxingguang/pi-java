package com.pijava.coding.agent.extension.bridge;

import java.util.concurrent.atomic.AtomicReference;

import com.pijava.coding.agent.core.CacheWarming;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionEventBus;
import com.pijava.coding.agent.extension.event.ExtensionEvent.CacheWarmingDecisionEvent;

import static com.pijava.coding.agent.extension.event.ExtensionEventSpec.CACHE_WARMING_DECISION;

/**
 * 批 13（docs/24 T3，裁决⑦）：pi {@code emitCacheWarmingDecision}
 * （runner.ts:1121-1142）的 java 桥——{@code cache_warming_decision} 事件线接到
 * {@link com.pijava.coding.agent.core.CacheWarmer} 的 decide 钩上。
 *
 * <p><b>晚绑定</b>（{@link ProviderEventBridge} :48-51 同款）：warmer 在
 * {@code wireSession} 创建、bus 在 {@code loadExtensions} 才建 ⇒ 桥先建后装；
 * 未安装（含 {@code --no-extensions}）或无订阅 ⇒ 返回事件自带 action
 * （pi sdk.ts:338 的 {@code ?? event.action} 臂）。</p>
 *
 * <p>语义逐字：<b>last override wins</b>（后处理器的非空 action 覆写前者）；
 * 处理器抛错 ⇒ {@code emitError} 后继续、已推进的决策不丢。warmer 侧还有一层
 * try/catch 回落（pi cache-warmer.ts:315-317）——两层同保留。</p>
 */
public final class CacheWarmingBridge {

    private final AtomicReference<ExtensionEventBus> busRef = new AtomicReference<>();
    private volatile ExtensionContext ctx;

    /** pi {@code extensionRunnerRef.current = runner}（装配回填点）。 */
    public void install(ExtensionEventBus bus, ExtensionContext ctx) {
        this.ctx = ctx;
        this.busRef.set(bus);
    }

    /**
     * pi {@code emitCacheWarmingDecision} 逐字：逐处理器快照派发，
     * {@code result.action() != null} ⇒ 覆写（last wins）。
     *
     * <p>方法引用即 {@code CacheWarmer.DecideHook}（装配点：
     * {@code bridge::emitOrDefault}）。</p>
     *
     * @return 最终动作；无覆写 ⇒ 事件自带的 pi 决策
     */
    public CacheWarming.Action emitOrDefault(CacheWarmingDecisionEvent event) {
        var bus = busRef.get();
        if (bus == null || !bus.hasHandlers(CACHE_WARMING_DECISION)) {
            return event.action();
        }
        var action = event.action();
        for (var handler : bus.snapshot(CACHE_WARMING_DECISION)) {
            try {
                var result = handler.handle(event, ctx);
                if (result != null && result.action() != null) {
                    action = result.action();
                }
            } catch (Throwable e) { // pi 的 catch 捕获一切：逐处理器上报 ⇒ 继续
                bus.emitError(handler.error(e));
            }
        }
        return action;
    }
}
