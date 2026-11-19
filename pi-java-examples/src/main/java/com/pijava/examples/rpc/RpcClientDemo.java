package com.pijava.examples.rpc;

/**
 * pi {@code examples/rpc-client.ts} 等价改写（docs/56 §2.3）——
 * 默认提示词、事件过滤谓词、工具前缀与 prompt 请求形状（形状差 D248）。
 */
public final class RpcClientDemo {

    /** pi L12：argv 拼接或默认提示词逐字。 */
    public static String promptOf(String... args) {
        if (args == null || args.length == 0) {
            return "Explain this repository in one paragraph.";
        }
        var joined = String.join(" ", args);
        return joined.isEmpty() ? "Explain this repository in one paragraph." : joined;
    }

    /** pi L43 工具前缀逐字。 */
    public static String toolPrefix(String toolName) {
        return "\n[tool: " + toolName + "]\n";
    }

    /** 事件类型判定（pi L42/L44）。 */
    public static boolean isTextDelta(String type, String nestedType) {
        return "message_update".equals(type) && "text_delta".equals(nestedType);
    }

    public static boolean isToolStart(String type) {
        return "tool_execution_start".equals(type);
    }

    /** pi 底层 prompt 请求形状（send 加 id req_N）。 */
    public record PromptRequest(String id, String type, String message) {}

    public static PromptRequest promptRequest(int requestId, String message) {
        return new PromptRequest("req_" + requestId, "prompt", message);
    }
}
