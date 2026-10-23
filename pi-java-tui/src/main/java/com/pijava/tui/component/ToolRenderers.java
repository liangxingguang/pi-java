package com.pijava.tui.component;

import com.pijava.coding.agent.extension.ToolCallRenderer;
import com.pijava.coding.agent.extension.ToolRendererRegistry;

/**
 * 批 16（T4d，docs/27）：tui 侧的工具渲染器静态持有者——{@code PiTuiApp} 在会话
 * attach/switch 时注入会话的 {@link ToolRendererRegistry}，{@link MessageBubble}
 * 渲染 ToolUse/ToolResult 块前查表。
 *
 * <p>静态持有而不是逐组件传参：MessageBubble 是纯静态投影函数（无实例态可携带
 * 注册表），pi 侧的对等物也是会话级单例（extensionRunner）。</p>
 */
public final class ToolRenderers {

    private static volatile ToolRendererRegistry registry;

    private ToolRenderers() {}

    /** 注入会话的渲染器注册表（{@code null} ⇒ 拆表，全部回落默认渲染）。 */
    public static void install(ToolRendererRegistry value) {
        registry = value;
    }

    /**
     * 解析 {@code toolName} 的渲染器；无注册表/全链不管 ⇒ {@code null}
     * （调用方回落 ToolCallCard 默认路径）。
     */
    public static ToolCallRenderer resolve(String toolName) {
        var current = registry;
        return current == null || toolName == null ? null : current.resolve(toolName, null);
    }
}
