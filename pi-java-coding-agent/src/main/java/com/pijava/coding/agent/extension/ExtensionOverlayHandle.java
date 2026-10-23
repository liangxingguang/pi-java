package com.pijava.coding.agent.extension;

/**
 * 批 17（T1，docs/28）：pi {@code OverlayHandle}（tui/tui.ts:307-330）的窄化三件
 * ——{@code focus/unfocus/getBounds} 不支持（java 无 overlay 栈，窄差 D99）。
 */
public interface ExtensionOverlayHandle {

    /** 关闭 overlay（pi {@code hide()}）。 */
    void hide();

    /** 临时摘/回挂 overlay（pi {@code setHidden}）。 */
    void setHidden(boolean hidden);

    /** 是否处于摘态（pi {@code isHidden}）。 */
    boolean isHidden();
}
