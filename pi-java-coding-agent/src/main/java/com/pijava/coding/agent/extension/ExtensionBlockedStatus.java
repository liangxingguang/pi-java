package com.pijava.coding.agent.extension;

import org.jspecify.annotations.Nullable;

/**
 * 扩展对话框的 OSC 7501 阻塞状态（docs/26 §4.3，K39）：pi
 * {@code ExtensionUIDialogOptions.blocked} 的 java 对位。
 *
 * @param kind    "permission" | "question" | "auth"
 * @param message 一行人类可读消息
 */
public record ExtensionBlockedStatus(@Nullable String kind, @Nullable String message) {

    /** 一般提问。 */
    public static ExtensionBlockedStatus question(String message) {
        return new ExtensionBlockedStatus("question", message);
    }

    /** 权限确认。 */
    public static ExtensionBlockedStatus permission(String message) {
        return new ExtensionBlockedStatus("permission", message);
    }
}
