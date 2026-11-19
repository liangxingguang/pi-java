package com.pijava.examples.rpc;

import java.util.List;

/**
 * pi {@code examples/rpc-extension-ui.ts} 等价改写（docs/56 §2.3）——
 * 九方法通道、三类回包、纯函数与事件顺序；TUI ANSI 绘制降级为纯文本标记（D250），
 * Java RpcExtensionUI 处理 setTitle 的差异登记 D252。
 */
public final class RpcExtensionUiDemo {

    /** pi 九方法（rpc-demo 头部枚举），逐字。 */
    public static List<String> methods() {
        return List.of("select", "confirm", "input", "editor", "notify", "setStatus",
            "setWidget", "setTitle", "set_editor_text");
    }

    /** 需回 extension_ui_response 的四个对话框方法。 */
    public static List<String> dialogMethods() {
        return List.of("select", "confirm", "input", "editor");
    }

    /** pi L118：dots 递增。 */
    public static int nextDots(int dots) {
        return (dots % 3) + 1;
    }

    /** pi L423：prefill 换行压平（editor）。 */
    public static String flattenPrefill(String prefill) {
        return prefill == null ? null : prefill.replace("\n", " ");
    }

    /** pi L571：结果截断 120。 */
    public static String truncateResult(String json) {
        return json.substring(0, Math.min(120, json.length()));
    }

    /** pi L84：日志上限 slice(-1000)。 */
    public static <T> List<T> tail(List<T> lines, int maxLines) {
        if (lines.size() <= maxLines) {
            return lines;
        }
        return lines.subList(lines.size() - maxLines, lines.size());
    }

    /** 三类回包形状逐字。 */
    public record Response(String id, String kind, Object value) {}

    public static Response withValue(String id, String value) {
        return new Response(id, "value", value);
    }

    public static Response confirmed(String id, boolean value) {
        return new Response(id, "confirmed", value);
    }

    public static Response cancelled(String id) {
        return new Response(id, "cancelled", true);
    }

    /** pi L521-584 事件顺序。 */
    public static List<String> eventOrder() {
        return List.of("response-error", "agent_start", "extension_ui_request",
            "message_update.text_delta", "tool_execution_start", "tool_execution_end",
            "agent_settled");
    }
}
