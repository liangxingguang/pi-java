package com.pijava.coding.agent.extension;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * 批 17（T1，docs/28）：{@code ctx.ui.custom} 的选项——pi types.ts:218-226 的
 * java 对位。
 *
 * <p>pi 的 {@code overlayOptions}（定位/尺寸全参）不移植——java 无 overlay 栈
 * （窄差 D99，docs/28 §5）。</p>
 *
 * @param overlay  true ⇒ 走 overlay 臂（java 与非 overlay 臂同路径——模态层，
 *                 窄差 D99；pi 默认 false 逐字）
 * @param onHandle overlay 展示后回调（窄化三件 {@link ExtensionOverlayHandle}）
 */
public record ExtensionCustomOptions(
    boolean overlay, @Nullable Consumer<ExtensionOverlayHandle> onHandle) {

    /** 默认（overlay=false，无 handle 回调——pi {@code options === undefined}）。 */
    public static final ExtensionCustomOptions DEFAULT = new ExtensionCustomOptions(false, null);
}
