package com.pijava.tui.component;

import com.pijava.coding.agent.extension.ExtensionEntryRenderer;
import com.pijava.coding.agent.extension.ExtensionMessageRenderer;

/**
 * 批 26（docs/37 T10）：tui 侧的 custom 渲染器静态持有者——{@code PiTuiApp} 在
 * 会话 attach/switch 时注入会话级解析缝（{@code AgentSession.messageRendererFor}/
 * {@code entryRendererFor}），{@code ChatMessage.project} 的 Custom/CustomMessage
 * 臂查表。静态持有的理由同 {@link ToolRenderers}（纯静态投影无实例态可携带）。
 */
public final class CustomRenderers {

    /** customType ⇒ 渲染器（无命中 ⇒ null）。 */
    @FunctionalInterface
    public interface Resolver<R> {
        /** 解析渲染器；无命中 ⇒ {@code null}。 */
        R resolve(String customType);
    }

    private static volatile Resolver<ExtensionMessageRenderer> messageResolver;
    private static volatile Resolver<ExtensionEntryRenderer> entryResolver;

    private CustomRenderers() {}

    /** 注入会话的解析缝（{@code null} ⇒ 拆表，全部回落/不渲）。 */
    public static void install(Resolver<ExtensionMessageRenderer> messages,
                               Resolver<ExtensionEntryRenderer> entries) {
        messageResolver = messages;
        entryResolver = entries;
    }

    /** pi {@code getMessageRenderer} 的消费缝；无表/无命中 ⇒ {@code null}。 */
    public static ExtensionMessageRenderer resolveMessage(String customType) {
        var current = messageResolver;
        return current == null || customType == null ? null : current.resolve(customType);
    }

    /** pi {@code getEntryRenderer} 的消费缝；无表/无命中 ⇒ {@code null}。 */
    public static ExtensionEntryRenderer resolveEntry(String customType) {
        var current = entryResolver;
        return current == null || customType == null ? null : current.resolve(customType);
    }
}
