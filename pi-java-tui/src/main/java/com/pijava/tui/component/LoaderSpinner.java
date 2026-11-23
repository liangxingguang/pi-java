package com.pijava.tui.component;

import java.util.List;

/**
 * L7 W1（docs/22 §4.1，C5）：pi {@code tui/src/components/loader.ts} 的应用层
 * 函数式等价——braille 10 帧动画＋帧间隔。
 *
 * <p>窄差 N3（docs/22 §5）：pi 的 {@code Loader} 是 tui 框架组件（{@code Text}
 * 子类＋{@code setInterval} 定时器＋每帧 {@code requestRender}）；java 无框架层
 * 组件模型 ⇒ 时间驱动的帧计算——fullscreen 由 33ms 整帧重绘逐帧重算、inline 由
 * 80ms 唤醒（{@code util.CountdownWake}）标脏，帧取值无漂移（原 docs/31 §8.38.4
 * 裁决点 B 同构）。</p>
 *
 * <p>pi 语义逐字：{@code frames} 空 ⇒ 无 spinner 符号（message 保留）；单帧 ⇒
 * 静态（无定时器，逐字渲染）；自定义帧表 verbatim（渲染层不加色，N4）。</p>
 */
public final class LoaderSpinner {

    /** pi {@code DEFAULT_FRAMES}（loader.ts:18）。 */
    public static final List<String> DEFAULT_FRAMES =
        List.of("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏");

    /** pi {@code DEFAULT_INTERVAL_MS}（loader.ts:19）。 */
    public static final long DEFAULT_INTERVAL_MS = 80;

    private LoaderSpinner() {}

    /**
     * 当前应渲染的帧。
     *
     * @param frames     帧表（空 ⇒ {@code ""}；单帧 ⇒ 静态取首帧）
     * @param intervalMs 帧间隔毫秒（&le;0 ⇒ pi {@code intervalMs > 0} 门 ⇒ 回默认）
     * @param startNanos 动画起点（{@code System.nanoTime()} 基）
     * @param nowNanos   当前时刻
     * @return 帧字符；空帧表 ⇒ 空串
     */
    public static String frameAt(List<String> frames, long intervalMs,
                                 long startNanos, long nowNanos) {
        if (frames == null || frames.isEmpty()) {
            return "";
        }
        long interval = intervalMs > 0 ? intervalMs : DEFAULT_INTERVAL_MS;
        if (frames.size() == 1) {
            return frames.get(0);
        }
        long elapsedMs = Math.max(0L, nowNanos - startNanos) / 1_000_000L;
        int index = (int) ((elapsedMs / interval) % frames.size());
        return frames.get(index);
    }
}
