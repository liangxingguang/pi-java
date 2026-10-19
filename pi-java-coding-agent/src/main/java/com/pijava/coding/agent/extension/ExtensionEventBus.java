package com.pijava.coding.agent.extension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventHandler;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * 扩展事件总线 —— pi 三处的合订：loader.ts:272-288（订阅/退订）、runner.ts:269-271
 * （{@code snapshotEventHandlers} 触发序）、runner.ts:742-746（{@code emitError}
 * 独立错误通道）＋:765-773（{@code hasHandlers}）。
 *
 * <p>订阅表按线名分桶，桶内注册序即触发序。pi 的表挂在单个扩展上、触发时按
 * 「扩展加载序 × 扩展内注册序」展开 —— java 的桶是全局追加序：装配期逐扩展加载、
 * 加载内逐次 {@code on()}，展开序与 pi 相同。⚠️ 若扩展在装配后**交错**运行期订阅，
 * 全局追加序会与 pi 的「按扩展分组」序不同（评审 M7 登记；当前无运行期订阅生产者）。</p>
 *
 * <p>线程模型：订阅集中在装配期（单线程），触发可并发（工具 worker 虚拟线程）——
 * 桶用 {@link CopyOnWriteArrayList}，表用 {@link ConcurrentHashMap}；
 * 「空表删键」用条件移除，竞态下残留空桶无害（{@link #hasHandlers} 看的是空与否）。</p>
 */
public final class ExtensionEventBus {

    /**
     * 一条已注册订阅（pi 的 {@code extension.handlers.get(event)} 数组元素＋扩展归属）。
     *
     * @param <E> 事件载荷类型
     * @param <R> 结果类型
     */
    public record RegisteredHandler<E extends ExtensionEvent, R>(
        String extensionName, ExtensionEventSpec<E, R> spec, ExtensionEventHandler<E, R> handler) {

        /** 调处理器（pi {@code handler(event, ctx)}）。 */
        public R handle(E event, ExtensionContext ctx) throws Exception {
            return handler.handle(event, ctx);
        }

        /** 按本订阅的归属构造错误对象（异常版）。 */
        public ExtensionError error(Throwable t) {
            return ExtensionError.of(extensionName, spec.name(), t);
        }

        /** 按本订阅的归属构造错误对象（文本版，无栈 —— pi 的「丢头系统」上报同形）。 */
        public ExtensionError error(String message) {
            return new ExtensionError(extensionName, spec.name(), message, null);
        }
    }

    private final Map<String, List<RegisteredHandler<?, ?>>> handlers = new ConcurrentHashMap<>();
    private final List<Consumer<ExtensionError>> errorListeners = new CopyOnWriteArrayList<>();

    /**
     * pi loader 的 {@code on()}（loader.ts:272-288 逐字语义）：注册序追加；返回的退订
     * 闭包按实例移除、空表删键。同一 handler 注册两次则各火一次（每次注册是独立条目）。
     */
    public <E extends ExtensionEvent, R> AutoCloseable on(String extensionName,
            ExtensionEventSpec<E, R> spec, ExtensionEventHandler<E, R> handler) {
        var entry = new RegisteredHandler<>(extensionName, spec, handler);
        var list = handlers.computeIfAbsent(spec.name(), k -> new CopyOnWriteArrayList<>());
        list.add(entry);
        return () -> {
            list.remove(entry);
            if (list.isEmpty()) {
                handlers.remove(spec.name(), list);
            }
        };
    }

    /** pi {@code hasHandlers}（runner.ts:765-773）：任一扩展订阅了该事件即为真。 */
    public boolean hasHandlers(ExtensionEventSpec<?, ?> spec) {
        var list = handlers.get(spec.name());
        return list != null && !list.isEmpty();
    }

    /**
     * pi {@code snapshotEventHandlers}（runner.ts:269-271）：触发前的快照，
     * 注册序。快照后发生的退订不影响本次触发（pi 的 {@code slice()} 同构）。
     */
    @SuppressWarnings("unchecked") // 桶按 spec.name() 分键，注册路径保证类型一致
    public <E extends ExtensionEvent, R> List<RegisteredHandler<E, R>> snapshot(
            ExtensionEventSpec<E, R> spec) {
        var list = handlers.get(spec.name());
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return List.copyOf((List<RegisteredHandler<E, R>>) (List<?>) list);
    }

    /** pi {@code runner.onError}（agent-session.ts:3341-3342 的消费点）。 */
    public void onError(Consumer<ExtensionError> listener) {
        errorListeners.add(listener);
    }

    /**
     * pi {@code emitError}（runner.ts:742-746 逐字）：错误**不进事件流**，走独立的
     * listener 集合。单个 listener 抛异常被隔离（会话事件广播同纪律）。
     */
    public void emitError(ExtensionError error) {
        for (var listener : errorListeners) {
            try {
                listener.accept(error);
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(ExtensionEventBus.class.getName())
                    .warning("extension_error listener threw: " + e);
            }
        }
    }
}
