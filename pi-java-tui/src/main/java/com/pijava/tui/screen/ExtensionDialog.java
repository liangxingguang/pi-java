package com.pijava.tui.screen;

import java.util.concurrent.CompletableFuture;

/**
 * 批 17（T4/T5，docs/28）：扩展对话框 overlay 的取消/结局缝——
 * {@code TuiExtensionUI} 的 abort/timeout/会话复位路径经它收口
 * （pi 的 {@code hideExtensionSelector/Input/Editor} 三件套在 java 的统一形）。
 */
public interface ExtensionDialog {

    /** 取消（结局＝取消值；幂等）。 */
    void cancel();

    /** 结局 future（宿主复位时补完成——{@code complete} 幂等）。 */
    CompletableFuture<?> resultFuture();

    /** pi CountdownTimer 的 onTick（无倒计时的对话框忽略）。 */
    default void countdownTick(int remainingSeconds) { }

    /** pi CountdownTimer 的 onExpire：到期＝取消。 */
    default void countdownExpired() {
        cancel();
    }
}
