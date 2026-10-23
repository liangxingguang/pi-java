package com.pijava.coding.agent.extension;

import org.jspecify.annotations.Nullable;

import com.pijava.ai.AbortSignal;

/**
 * 批 17（T1，docs/28）：pi {@code ExtensionUIDialogOptions}（types.ts:114-119 逐字）——
 * 扩展对话框（select/confirm/input）的选项。
 *
 * @param signal        编程式关闭对话框的信号（pi {@code AbortSignal} 对位；
 *                      null ≙ 键省略）
 * @param timeoutMillis 毫秒超时——到期自动取消并带倒计时显示（null ≙ 键省略）
 */
public record ExtensionUiDialogOptions(
    @Nullable AbortSignal signal, @Nullable Long timeoutMillis) {

    /** 无选项（≙ pi 调用方省略 {@code opts}）。 */
    public static final ExtensionUiDialogOptions NONE = new ExtensionUiDialogOptions(null, null);
}
