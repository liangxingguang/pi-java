package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * L7 W6（S2c，pi {@code components/alt-screen-flash.ts} 对位）：短暂状态行。
 *
 * <p>每条 flash 独立到期；到期由渲染 tick 现读（{@link #messagesAt}），不引入
 * 定时器——与 pi 的 {@code flash(msg, durationMs)} 可观察行为对齐（视觉反白、
 * 默认 1000 ms，错误 5000 ms）。{@link #messageAt} 保留单条读面以兼容旧调用。</p>
 */
final class FlashNotice {

    private record Entry(String message, long expiresAtNanos) { }

    private final List<Entry> entries = new ArrayList<>();

    /** 显示 1000 ms。 */
    void show(String text) {
        show(text, 1000);
    }

    /** 显示指定时长。 */
    void show(String text, long durationMs) {
        if (text == null || text.isEmpty()) {
            clear();
            return;
        }
        entries.add(new Entry(text,
            System.nanoTime() + Math.max(0, durationMs) * 1_000_000L));
    }

    /** 当前应显示的第一条消息；过期/无消息 ⇒ null。 */
    String messageAt(long nowNanos) {
        var messages = messagesAt(nowNanos);
        return messages.isEmpty() ? null : messages.get(0);
    }

    /** 当前应显示的所有消息（按 show 顺序；清理过期项）。 */
    List<String> messagesAt(long nowNanos) {
        entries.removeIf(entry -> nowNanos >= entry.expiresAtNanos());
        var messages = new ArrayList<String>(entries.size());
        for (var entry : entries) {
            messages.add(entry.message());
        }
        return messages;
    }

    /** 清空。 */
    void clear() {
        entries.clear();
    }
}
