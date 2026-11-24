package com.pijava.tui.screen;

/**
 * L7 W6（S2c，pi {@code components/alt-screen-flash.ts} 对位）：短暂状态行。
 *
 * <p>单条消息（新 flash 覆盖旧值）；到期由渲染 tick 现读（{@link #messageAt}），
 * 不引入定时器——与 pi 的 {@code flash(msg, durationMs)} 同可观察行为（视觉反白、
 * 默认 1000 ms，错误 5000 ms）。栈式多条为窄差 N4。</p>
 */
final class FlashNotice {

    private String message;
    private long expiresAtNanos;

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
        message = text;
        expiresAtNanos = System.nanoTime() + Math.max(0, durationMs) * 1_000_000L;
    }

    /** 当前应显示的消息；过期/无消息 ⇒ null。 */
    String messageAt(long nowNanos) {
        if (message == null) {
            return null;
        }
        if (nowNanos >= expiresAtNanos) {
            message = null;
            return null;
        }
        return message;
    }

    /** 清空。 */
    void clear() {
        message = null;
        expiresAtNanos = 0;
    }
}
