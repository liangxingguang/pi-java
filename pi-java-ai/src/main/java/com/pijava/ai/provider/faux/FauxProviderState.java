package com.pijava.ai.provider.faux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.pijava.ai.message.DeferredHandle;

/**
 * docs/20 §3.2：pi {@code FauxProviderState}（{@code providers/faux.ts:101-105}）。
 *
 * <p>计数比 pi 的单线程模型更强：队列与 map 由 {@link FauxCore} 保护，本类只用
 * 原子计数与并发列表，不改变可观察顺序。</p>
 */
public final class FauxProviderState {

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger fetches = new AtomicInteger();
    private final List<DeferredHandle> cancelled = new CopyOnWriteArrayList<>();

    /** 请求次数（空队列的错误请求也算）。 */
    public int callCount() {
        return calls.get();
    }

    /** {@code fetchDeferred} 调用次数（未知、已取消、失败的取回也算）。 */
    public int deferredFetchCount() {
        return fetches.get();
    }

    /** 已取消 handle 的快照（按取消顺序）。 */
    public List<DeferredHandle> cancelledDeferred() {
        return List.copyOf(cancelled);
    }

    void recordCall() {
        calls.incrementAndGet();
    }

    void recordDeferredFetch() {
        fetches.incrementAndGet();
    }

    void recordCancelled(DeferredHandle handle) {
        cancelled.add(handle);
    }
}
